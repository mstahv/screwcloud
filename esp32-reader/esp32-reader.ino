/*
  ScrewCloud sensor firmware for the ESP32-S3 and the ESP32-C3.

  Reads Ruuvi devices over BLE advertisements and sends the readings to the
  server over the board's own WiFi:
    1. RuuviTags (Data Format 5 / RAWv2), several supported
    2. Ruuvi Airs (Data Format 6), whose temperature and humidity travel like a
       tag's and whose CO2 and PM2.5 travel with them
    3. The chip's own die temperature, as a diagnostic sensor

  That is the whole list, on purpose. Nothing is wired to this board: no DHT22,
  no display, no NB-IoT modem. For those, use the temperature-reader sketch on a
  Raspberry Pi Pico 2 W. What this sketch shares with that one is everything
  that is not about hardware — the wire format, the Ruuvi decoding, the way a
  failing link is reported and, eventually, restarted around — and where the two
  can be the same they are, line for line, because ProtocolSyncTest in the
  server module reads both and complains when they drift.

  It works in cycles rather than continuously. The radios are what make an
  ESP32 hot: scanning for advertisements without pause and holding a WiFi
  association cost more than the rest of the chip put together, and a device
  that reports every five minutes needs neither for more than a fraction of
  that. So each cycle listens for a short window, sends what it heard, switches
  both radios off and waits — awake by default, so the light and the serial
  console keep working, or in light sleep if config.h asks for it. See
  "Power" in config.h.example for the numbers.

  One sketch for two chips. The S3 is a dual-core Xtensa and the C3 a
  single-core RISC-V, and nothing here can tell the difference: the Arduino
  core, NimBLE, WiFi, the watchdog and the RGB LED call are the same on both.
  The one thing that differs is which pin the LED hangs on, and config.h picks
  that by the target the compiler is building for.

  Developed against the Waveshare ESP32-S3-Zero and ESP32-C3-Zero, neither of
  which has a plain LED — status is shown on their WS2812 RGB LED instead, where
  the colour carries the meaning.

  Libraries: NimBLE-Arduino (2.x) from Library Manager. Nothing else; WiFi,
  neopixelWrite() and temperatureRead() come with the ESP32 Arduino core (3.x).

  Wire format and other details: see the project README.md
  RuuviTag data format:
  https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2
*/

#include <WiFi.h>
#include <WiFiUdp.h>
#include <NimBLEDevice.h>
#include <esp_sleep.h>
#include <esp_task_wdt.h>

#include "config.h"
#include "Protocol.h"

// Ruuvi Innovations Ltd, Bluetooth SIG company identifier (little endian on the wire)
static const uint16_t RUUVI_COMPANY_ID = 0x0499;
static const uint8_t RUUVI_FORMAT_5 = 0x05;
static const uint8_t RUUVI_FORMAT_6 = 0x06;  // Ruuvi Air, legacy-size advertisement
static const uint8_t RUUVI_FORMAT_6_LEN = 20;  // bytes after the company id
static const uint8_t RUUVI_FORMAT_5_LEN = 24;  // bytes after the company id

/*
   config.h is gitignored, so a copy written before the chip temperature existed
   has neither of these. Defaulting the id here keeps such a config compiling;
   ENABLE_INTERNAL_TEMPERATURE is deliberately not defaulted, so an old config
   keeps behaving exactly as it did until its owner opts in.
*/
#ifndef INTERNAL_SENSOR_ID
#define INTERNAL_SENSOR_ID "CPU"
#endif

/*
   Likewise for the power settings, which arrived after the first devices were
   configured. The defaults are the awake cycle described in config.h.example;
   light sleep stays off until a config asks for it.
*/
#ifndef LISTEN_MS
#define LISTEN_MS 20000UL
#endif
#ifndef CPU_FREQUENCY_MHZ
#define CPU_FREQUENCY_MHZ 80
#endif

/*
   How many sends in a row may fail before the device restarts itself. A failing
   link retries every RETRY_INTERVAL_MS rather than every SEND_INTERVAL_MS, so
   six of them is about six minutes — long enough that a passing network problem
   has had its chance to pass.

   This is the last resort, below WiFi's own reconnecting. It exists because the
   state that stops a link working is not always reachable from here: a WiFi
   stack that has wedged reports itself connected and sends nothing.
*/
static const uint8_t REBOOT_AFTER_FAILURES = 6;

/*
   How long the device must have been up before it is allowed to restart itself.

   What this protects against is a device that is misconfigured rather than
   stuck — a wrong password — rebooting every few minutes and rolling the
   explanation off the top of the serial log before anyone can read it. Half an
   hour between restarts leaves the log sitting there to be read, and still gets
   a genuinely stuck device forty-odd attempts a day at fixing itself.
*/
static const unsigned long MIN_UPTIME_BEFORE_REBOOT_MS = 30UL * 60UL * 1000UL;

/*
   The hardware watchdog, for the hang that no amount of link logic can see —
   a task that never yields, a peripheral that stalls, a loop this code did not
   imagine.

   Eight seconds, the same as the Pico's, and far shorter than a legitimate WiFi
   connect, which is allowed twenty. So it is fed from the connect wait as well
   as from loop(): a wait that is progressing feeds the dog, and one that has
   stopped progressing does not.
*/
static const uint32_t WATCHDOG_TIMEOUT_MS = 8000;

// Ruuvi fields are big endian.
static int16_t readInt16(const uint8_t *p) {
  return (int16_t)((p[0] << 8) | p[1]);
}

static uint16_t readUint16(const uint8_t *p) {
  return (uint16_t)((p[0] << 8) | p[1]);
}

/*
   Human readable names for tags, for the serial log — this board has no display
   and sends nothing but the identifier below, so naming a tag here is purely a
   convenience while watching the console. The measuring point's real name
   belongs in the web interface.

   The MAC of a new tag is printed on the serial console.
*/
struct RuuviTagName {
  uint8_t mac[6];
  const char *name;
};

static const RuuviTagName RUUVI_NAMES[] = {
  {{0xF3, 0x19, 0x1A, 0xC0, 0x8E, 0xBF}, "cold room"},
  /*
     A Ruuvi Air's advertisement carries only the low three bytes of its
     address, so it is keyed here with the first three as zeros:
     {{0x00, 0x00, 0x00, 0x4C, 0x88, 0x4F}, "living room"},
  */
};

static const char *ruuviNameFor(const uint8_t mac[6]) {
  for (const RuuviTagName &entry : RUUVI_NAMES) {
    if (memcmp(entry.mac, mac, 6) == 0) {
      return entry.name;
    }
  }
  return nullptr;
}

/*
   Only the fields this firmware reports, plus what the serial log wants to show
   about a Ruuvi Air. The Pico version also decodes pressure, acceleration and
   battery voltage for its log; here they are skipped to keep things minimal.
*/
struct RuuviReading {
  bool valid = false;
  uint8_t mac[6] = {0};
  float temperature = NAN;  // °C
  float humidity = NAN;     // %RH
  float co2 = NAN;          // ppm, Ruuvi Air only
  float pm25 = NAN;         // µg/m³, Ruuvi Air only
  int rssi = 0;
  unsigned long receivedAt = 0;

  bool matches(const uint8_t otherMac[6]) const {
    return valid && memcmp(mac, otherMac, sizeof(mac)) == 0;
  }

  bool isStale() const {
    return millis() - receivedAt > RUUVI_STALE_MS;
  }

  bool isAir() const {
    return !isnan(co2) || !isnan(pm25);
  }

  /*
     The identifier the server ties readings to: the low twelve bits of the MAC,
     and nothing else. Not a name from the table above, so that renaming a
     measuring point never splits a sensor's history in two, and not a running
     number, so that it survives a reboot. Kept identical to the Pico firmware —
     the same tag must not arrive under two different identifiers depending on
     which board happened to hear it.
  */
  void sensorIdTo(char *buffer, size_t size) const {
    snprintf(buffer, size, "R%03X", ((mac[4] & 0x0F) << 8) | mac[5]);
  }

  void fillReading(SensorReading &reading) const {
    sensorIdTo(reading.id, sizeof(reading.id));
    reading.temperature = temperature;
    reading.humidity = humidity;
    /*
       NAN on every tag that is not a Ruuvi Air, and the packer leaves out what
       is NAN — so a plain tag's record is the same size it always was, and no
       caller has to know which kind of device it is holding.
    */
    reading.co2 = co2;
    reading.pm25 = pm25;
  }

  /*
     data points at the format byte (0x05); len is the number of bytes left.
     Fields carrying their "invalid" value are left as NaN.
  */
  bool parseFormat5(const uint8_t *data, uint8_t len) {
    if (len < RUUVI_FORMAT_5_LEN || data[0] != RUUVI_FORMAT_5) {
      return false;
    }

    int16_t rawTemperature = readInt16(&data[1]);
    if (rawTemperature != (int16_t)0x8000) {
      temperature = rawTemperature * 0.005f;
    }

    uint16_t rawHumidity = readUint16(&data[3]);
    if (rawHumidity != 0xFFFF) {
      humidity = rawHumidity * 0.0025f;
    }

    memcpy(mac, &data[18], sizeof(mac));

    valid = true;
    receivedAt = millis();
    return true;
  }

  /*
     Fills this reading from a Ruuvi Data Format 6 packet, which is what a Ruuvi
     Air broadcasts in a legacy-size advertisement. data points at the format
     byte (0x06) and len is the number of bytes remaining.

     Everything the server can receive is a plain 16-bit field here, so the
     format's 9-bit values (VOC, NOx, sound) are simply not decoded — the packet
     has no room for them anyway.

     The advertisement carries only the low three bytes of the address; the high
     three stay zero. The identifier the server sees is derived from the last
     twelve bits, so it is exactly what the full address would give — the same
     rule, and the same value, as on the Pico hearing the same device.
  */
  bool parseFormat6(const uint8_t *data, uint8_t len) {
    if (len < RUUVI_FORMAT_6_LEN || data[0] != RUUVI_FORMAT_6) {
      return false;
    }

    int16_t rawTemperature = readInt16(&data[1]);
    if (rawTemperature != (int16_t)0x8000) {
      temperature = rawTemperature / 200.0f;
    }

    uint16_t rawHumidity = readUint16(&data[3]);
    if (rawHumidity != 0xFFFF) {
      humidity = rawHumidity / 400.0f;
    }

    uint16_t rawPm25 = readUint16(&data[7]);
    if (rawPm25 != 0xFFFF) {
      pm25 = rawPm25 / 10.0f;
    }

    uint16_t rawCo2 = readUint16(&data[9]);
    if (rawCo2 != 0xFFFF) {
      co2 = rawCo2;
    }

    memset(mac, 0, sizeof(mac));
    memcpy(&mac[3], &data[17], 3);

    valid = true;
    receivedAt = millis();
    return true;
  }

  void printTo(Print &out) const {
    char id[PROTOCOL_ID_SIZE + 1];
    sensorIdTo(id, sizeof(id));
    const char *name = ruuviNameFor(mac);
    out.printf("%s %s%s%s %02X:%02X:%02X:%02X:%02X:%02X  %.2f C  %.2f %%  RSSI %d dBm  (%lu ms ago)%s\n",
               isAir() ? "Ruuvi Air" : "RuuviTag",
               id, name != nullptr ? " / " : "", name != nullptr ? name : "",
               mac[0], mac[1], mac[2], mac[3], mac[4], mac[5],
               temperature, humidity, rssi,
               millis() - receivedAt, isStale() ? "  [STALE]" : "");
    if (isAir()) {
      out.printf("  CO2 %.0f ppm, PM2.5 %.1f ug/m3\n", co2, pm25);
    }
  }
};

/* ==========================================================================
   Registry

   Written from the NimBLE host task and read from loop(), so every access is
   under a mutex. This is the one real structural difference from the Pico
   firmware: there, BTstack callbacks ran inside BTstack.loop() on the main
   thread and no locking was needed.

   The critical sections are a few struct copies, so holding the lock cannot
   meaningfully delay the BLE task.
   ========================================================================== */
class RuuviRegistry {
public:
  void begin() {
    mutex = xSemaphoreCreateMutex();
  }

  void store(const RuuviReading &reading) {
    // begin() must run before scanning starts; guard anyway rather than let
    // FreeRTOS assert on a null handle.
    if (mutex == nullptr || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) {
      return;
    }
    RuuviReading *slot = findSlot(reading.mac);
    if (slot != nullptr) {
      *slot = reading;
    } else {
      dropped++;
    }
    xSemaphoreGive(mutex);
  }

  /** Snapshot for loop() to work on without holding the lock. */
  uint8_t snapshot(RuuviReading *out, uint8_t capacity) {
    uint8_t count = 0;
    // begin() must run before scanning starts; guard anyway rather than let
    // FreeRTOS assert on a null handle.
    if (mutex == nullptr || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) {
      return 0;
    }
    for (const RuuviReading &tag : tags) {
      if (tag.valid && count < capacity) {
        out[count++] = tag;
      }
    }
    xSemaphoreGive(mutex);
    return count;
  }

  unsigned long droppedCount() const {
    return dropped;
  }

private:
  RuuviReading tags[MAX_RUUVI_TAGS];
  unsigned long dropped = 0;
  SemaphoreHandle_t mutex = nullptr;

  /** Existing slot for this MAC, or the first free one, or nullptr if full. */
  RuuviReading *findSlot(const uint8_t mac[6]) {
    for (RuuviReading &tag : tags) {
      if (tag.matches(mac)) {
        return &tag;
      }
    }
    for (RuuviReading &tag : tags) {
      if (!tag.valid) {
        return &tag;
      }
    }
    return nullptr;
  }
};

static RuuviRegistry ruuviTags;

/* ==========================================================================
   BLE scanning
   ========================================================================== */

/*
   NimBLE hands over the manufacturer specific field with the company id as its
   first two bytes and with the real length — no need to walk the AD structures
   by hand as on the Pico, where BTstack always copies a full 31 bytes and hides
   the actual length.
*/
class RuuviScanCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice *device) override {
    if (!device->haveManufacturerData()) {
      return;
    }
    std::string data = device->getManufacturerData();
    // Two bytes of company id and at least the shorter of the two formats.
    if (data.size() < 2 + RUUVI_FORMAT_6_LEN) {
      return;
    }

    const uint8_t *bytes = reinterpret_cast<const uint8_t *>(data.data());
    uint16_t companyId = bytes[0] | (bytes[1] << 8);  // little endian
    if (companyId != RUUVI_COMPANY_ID) {
      return;
    }

    RuuviReading reading;
    if (reading.parseFormat5(&bytes[2], data.size() - 2)
        || reading.parseFormat6(&bytes[2], data.size() - 2)) {
      reading.rssi = device->getRSSI();
      ruuviTags.store(reading);
    }
  }
};

static RuuviScanCallbacks scanCallbacks;

static void setupScanning() {
  NimBLEDevice::init("");

  NimBLEScan *scan = NimBLEDevice::getScan();
  /*
     wantDuplicates = true is essential. Without it the duplicate filter lets
     each tag through only once and the readings would never update.
  */
  scan->setScanCallbacks(&scanCallbacks, true);
  scan->setActiveScan(false);  // passive: Ruuvi puts everything in the advertisement
  scan->setInterval(SCAN_INTERVAL_MS);
  scan->setWindow(SCAN_WINDOW_MS);
  // 0 = do not accumulate a result list; we only care about the callbacks.
  scan->setMaxResults(0);
}

/*
   Scanning is the single most expensive thing this device does — the receiver
   is on for the whole window, and the window is the whole interval — so it runs
   only while there is something to listen for. Started at the top of each cycle,
   stopped before the send.
*/
static void startScanning() {
  NimBLEScan *scan = NimBLEDevice::getScan();
  if (!scan->isScanning()) {
    scan->start(0);  // 0 = until stopped
  }
}

static void stopScanning() {
  NimBLEScan *scan = NimBLEDevice::getScan();
  if (scan->isScanning()) {
    scan->stop();
  }
}

/* ==========================================================================
   The chip's own temperature

   A diagnostic sensor, not a thermometer: it sits on the same silicon as the
   CPU and the radios, so it reads well above the room and rises further the
   busier the chip is. It is included because watching it is interesting — the
   shape of the curve says something about the load and the enclosure — not
   because the number means anything about the air. The server knows the
   identifier and shows it on the device's status line rather than as a card.

   It needs no hardware and cannot fail, so there is nothing to detect: if it is
   enabled, it reports.
   ========================================================================== */
struct InternalTemperature {
  float temperature = NAN;  // °C

  void update() {
#ifdef ENABLE_INTERNAL_TEMPERATURE
    temperature = temperatureRead();
#endif
  }

  bool hasReading() const {
    return !isnan(temperature);
  }

  void fillReading(SensorReading &reading) const {
    // "%s" rather than the macro as the format: the id comes from config.h, and
    // a stray % in it would otherwise be read as a conversion.
    snprintf(reading.id, sizeof(reading.id), "%s", INTERNAL_SENSOR_ID);
    reading.temperature = temperature;
    // No humidity: the server shows the missing value as a dash rather than 0 %.
    reading.humidity = NAN;
  }

  void printTo(Print &out) const {
    if (!hasReading()) {
      out.println("Core temperature: off");
      return;
    }
    out.printf("Core temperature: %2.1f C (chip, not the room)\n", (double)temperature);
  }
};

static InternalTemperature internalTemperature;

/* ==========================================================================
   Link state
   ========================================================================== */

enum class LinkStatus : uint8_t { Unknown, Ok, Failed };

/*
   Result of the most recent send. The LED reads this, and so does the serial
   log, so they cannot end up telling different stories.
*/
struct LinkState {
  LinkStatus status = LinkStatus::Unknown;
  unsigned long lastSuccessAt = 0;
  bool everSucceeded = false;

  /*
     Why the last send failed. "FAILED" on its own says that something is wrong
     but not whether to check the password, the router or the server — and those
     are three different afternoons. The words are the Pico firmware's, so a
     reader who knows one board knows the other.
  */
  const char *reason = "";

  /*
     Consecutive failures. WiFi heals what it can on its own; this is the count
     that decides when the whole device has been unwell long enough to be worth
     restarting.
  */
  uint8_t failures = 0;

  void recordResult(bool ok, const char *failureReason) {
    status = ok ? LinkStatus::Ok : LinkStatus::Failed;
    if (ok) {
      lastSuccessAt = millis();
      everSucceeded = true;
      failures = 0;
      reason = "";
    } else {
      if (failures < 255) {
        failures++;
      }
      reason = failureReason != nullptr ? failureReason : "";
    }
  }
};

static LinkState linkState;

/* ==========================================================================
   Status LED

   The Zero boards have no plain LED, only a WS2812 on RGB_LED_PIN. The colour
   carries the state and the rhythm reinforces it, which reads better across a
   room than rhythm alone. The rhythms are the Pico's, so the two boards can be
   read the same way:
     ok       two short flashes and a long dark pause, green
     failed   fast steady pulsing, red
     unknown  calm steady blinking, blue
   ========================================================================== */

static const uint16_t BLINK_STEPS_OK[] = {80, 200, 80, 2600};
static const uint16_t BLINK_STEPS_FAILED[] = {100, 100};
static const uint16_t BLINK_STEPS_UNKNOWN[] = {500, 500};

struct StatusLed {
  void begin() {
    applyStep();
  }

  void setStatus(LinkStatus newStatus) {
    if (newStatus == status) {
      return;
    }
    status = newStatus;
    step = 0;
    stepStartedAt = millis();
    applyStep();
  }

  // Called on every loop() iteration, never blocks.
  void update() {
    if (millis() - stepStartedAt < steps()[step]) {
      return;
    }
    step = (step + 1) % stepCount();
    stepStartedAt = millis();
    applyStep();
  }

private:
  LinkStatus status = LinkStatus::Unknown;
  uint8_t step = 0;
  unsigned long stepStartedAt = 0;

  const uint16_t *steps() const {
    switch (status) {
      case LinkStatus::Ok:     return BLINK_STEPS_OK;
      case LinkStatus::Failed: return BLINK_STEPS_FAILED;
      default:                 return BLINK_STEPS_UNKNOWN;
    }
  }

  uint8_t stepCount() const {
    switch (status) {
      case LinkStatus::Ok:     return sizeof(BLINK_STEPS_OK) / sizeof(BLINK_STEPS_OK[0]);
      case LinkStatus::Failed: return sizeof(BLINK_STEPS_FAILED) / sizeof(BLINK_STEPS_FAILED[0]);
      default:                 return sizeof(BLINK_STEPS_UNKNOWN) / sizeof(BLINK_STEPS_UNKNOWN[0]);
    }
  }

  void applyStep() {
    bool lit = (step % 2 == 0);
    if (!lit) {
      neopixelWrite(RGB_LED_PIN, 0, 0, 0);
      return;
    }
    switch (status) {
      case LinkStatus::Ok:     neopixelWrite(RGB_LED_PIN, 0, RGB_LED_BRIGHTNESS, 0); break;
      case LinkStatus::Failed: neopixelWrite(RGB_LED_PIN, RGB_LED_BRIGHTNESS, 0, 0); break;
      default:                 neopixelWrite(RGB_LED_PIN, 0, 0, RGB_LED_BRIGHTNESS); break;
    }
  }
};

static StatusLed statusLed;

/* ==========================================================================
   WiFi and sending
   ========================================================================== */

static WiFiUDP udp;

static bool connectWiFi() {
  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (millis() - start > WIFI_CONNECT_TIMEOUT_MS) {
      Serial.println("WiFi: connect timed out");
      return false;
    }
    /*
       delay() rather than a busy loop: on the ESP32 this yields to the WiFi and
       BLE tasks, which need CPU time to make progress. Unlike on the Pico there
       is no run loop of ours to pump here — but the watchdog still has to be
       fed, because this wait is allowed to outlast it.
    */
    esp_task_wdt_reset();
    delay(50);
  }
  Serial.printf("WiFi: connected, IP %s\n", WiFi.localIP().toString().c_str());
  return true;
}

/*
   The radio off between sends. An association costs tens of milliamps just to
   keep, and connecting again takes a few seconds against an interval of minutes.
   connectWiFi() brings it back when the next packet is ready.
*/
static void disconnectWiFi() {
  WiFi.disconnect(true /* radio off */);
  WiFi.mode(WIFI_OFF);
}

/*
   Sends one packet and says why not if it could not. The reasons are the Pico
   WiFi transport's, word for word.
*/
static bool sendPacket(const MeasurementPacket &packet, const char *&failure) {
  if (!connectWiFi()) {
    failure = "NO WIFI";
    return false;
  }
  if (udp.beginPacket(SERVER_HOST, SERVER_PORT) != 1) {
    // Almost always the server name not resolving.
    failure = "NO HOST";
    return false;
  }
  udp.write(packet.data(), packet.size());
  if (udp.endPacket() != 1) {
    failure = "NO SEND";
    return false;
  }
  failure = "";
  return true;
}

/*
   Assembles and sends a measurement packet. Stale tags are left out: the last
   reading of a dead tag is not a measurement, and on the server it would look
   fresh.
*/
static void reportMeasurements() {
  static uint16_t sequence = 0;

  MeasurementPacket packet;
  packet.begin(DEVICE_ID, ++sequence);

  SensorReading reading;
  internalTemperature.update();
  if (internalTemperature.hasReading()) {
    internalTemperature.fillReading(reading);
    packet.add(reading);
  }

  RuuviReading readings[MAX_RUUVI_TAGS];
  uint8_t count = ruuviTags.snapshot(readings, MAX_RUUVI_TAGS);
  for (uint8_t i = 0; i < count; i++) {
    if (readings[i].isStale()) {
      continue;
    }
    readings[i].fillReading(reading);
    packet.add(reading);
  }

  // Nothing to report yet; no point spending a transmission on an empty packet.
  if (packet.sensorCount() == 0) {
    Serial.println("Send skipped: no sensor readings yet");
    return;
  }

  const char *failure = "";
  bool sent = sendPacket(packet, failure);
  linkState.recordResult(sent, failure);
  statusLed.setStatus(linkState.status);

  if (sent) {
    Serial.printf("Send ok: %u sensors, %u bytes, sequence %u\n",
                  packet.sensorCount(), packet.size(), sequence);
  } else {
    Serial.printf("Send FAILED (%s), %u in a row: %u sensors, %u bytes, sequence %u\n",
                  linkState.reason, linkState.failures,
                  packet.sensorCount(), packet.size(), sequence);
  }

  rebootIfHopeless();
}

/*
   The last resort, when WiFi's own reconnecting has not helped for several
   attempts running. Everything this device knows is either in the tags
   themselves or on the server, so a restart costs a few minutes of readings and
   nothing else — and it clears any state that is stuck somewhere this code
   cannot reach, in the network stack or the radio.

   One thing holds it back: a boot too young to have earned another one
   (MIN_UPTIME_BEFORE_REBOOT_MS). Nothing here waits for a person the way a SIM
   PIN does on the Pico, so that is the only check.
*/
static void rebootIfHopeless() {
  if (linkState.failures < REBOOT_AFTER_FAILURES) {
    return;
  }
  if (millis() < MIN_UPTIME_BEFORE_REBOOT_MS) {
    Serial.printf("Link has been down for %u sends (%s), but this boot is only "
                  "%lu s old. Leaving the log alone for now.\n",
                  linkState.failures, linkState.reason, millis() / 1000);
    return;
  }
  Serial.printf("Link has been down for %u sends (%s). Restarting.\n",
                linkState.failures, linkState.reason);
  Serial.flush();
  delay(200);
  ESP.restart();
}

static void printReadings() {
  RuuviReading readings[MAX_RUUVI_TAGS];
  uint8_t count = ruuviTags.snapshot(readings, MAX_RUUVI_TAGS);

  internalTemperature.update();
  internalTemperature.printTo(Serial);
  if (count == 0) {
    Serial.println("RuuviTag: nothing heard yet");
  }
  for (uint8_t i = 0; i < count; i++) {
    readings[i].printTo(Serial);
  }
  if (ruuviTags.droppedCount() > 0) {
    Serial.printf("RuuviTag: %lu advertisements dropped, MAX_RUUVI_TAGS (%u) full\n",
                  ruuviTags.droppedCount(), MAX_RUUVI_TAGS);
  }
}

/*
   Arms the task watchdog and puts this task under it. The Arduino core may
   already have the watchdog running with its own timeout, in which case
   initialising it again is refused and it is reconfigured instead — the
   outcome is the same either way: our timeout, watching our loop.
*/
static void startWatchdog() {
  esp_task_wdt_config_t config = {};
  config.timeout_ms = WATCHDOG_TIMEOUT_MS;
  config.idle_core_mask = 0;
  config.trigger_panic = true;
  if (esp_task_wdt_init(&config) == ESP_ERR_INVALID_STATE) {
    esp_task_wdt_reconfigure(&config);
  }
  esp_task_wdt_add(NULL);
}

/* ==========================================================================
   The cycle

   Two phases, and the radios follow them:

     listening   BLE scanning, for LISTEN_MS. Long enough that every tag in
                 range has advertised several times over.
     resting     both radios off, until the next cycle is due. Awake, unless
                 LIGHT_SLEEP_BETWEEN_SENDS is defined — then the chip sleeps
                 and wakes on its timer.

   The send sits between the two: scanning stops, WiFi comes up, the packet
   goes, WiFi goes down. A failed send shortens the rest to RETRY_INTERVAL_MS.

   The first cycle starts at boot, so the first packet leaves about LISTEN_MS
   after power-on — the same wait as the old FIRST_SEND_DELAY_MS, for the same
   reason.
   ========================================================================== */

enum class Phase : uint8_t { Listening, Resting };

static Phase phase = Phase::Listening;
static unsigned long phaseStartedAt = 0;
static unsigned long restFor = 0;

static void startListening() {
  phase = Phase::Listening;
  phaseStartedAt = millis();
  startScanning();
  Serial.printf("Listening for %lu s\n", (unsigned long)(LISTEN_MS / 1000UL));
}

static void startResting() {
  stopScanning();
  reportMeasurements();
  disconnectWiFi();

  phase = Phase::Resting;
  phaseStartedAt = millis();
  /*
     The interval is measured send to send, so the rest is what is left of it
     after the listening window — and shorter after a failure, so a link that
     comes back is noticed within a minute rather than five.
  */
  unsigned long interval = linkState.status == LinkStatus::Failed
                               ? RETRY_INTERVAL_MS : SEND_INTERVAL_MS;
  restFor = interval > LISTEN_MS ? interval - LISTEN_MS : 0;
  Serial.printf("Resting for %lu s\n", restFor / 1000UL);
}

#ifdef LIGHT_SLEEP_BETWEEN_SENDS
/*
   Light sleep until the next cycle: CPU halted, RAM kept, radios already off.
   Two things have to be arranged around it. The watchdog's timer would fire the
   moment the chip wakes, having "missed" its feedings for minutes, so this task
   leaves the watchdog's care before sleeping and returns to it after. And the
   light is switched off — it cannot be blinked from a halted CPU, and a light
   frozen on would read as a state that does not exist.

   The USB console drops while the chip sleeps and comes back after; that is the
   price of this mode, and why it is not the default.
*/
static void sleepUntilNextCycle() {
  unsigned long elapsed = millis() - phaseStartedAt;
  if (elapsed >= restFor) {
    return;
  }
  unsigned long remaining = restFor - elapsed;
  neopixelWrite(RGB_LED_PIN, 0, 0, 0);
  Serial.printf("Light sleep for %lu s\n", remaining / 1000UL);
  Serial.flush();

  esp_task_wdt_delete(NULL);
  esp_sleep_enable_timer_wakeup((uint64_t)remaining * 1000ULL);
  esp_light_sleep_start();
  esp_task_wdt_add(NULL);
  esp_task_wdt_reset();
}
#endif

void setup() {
  Serial.begin(115200);

  /*
     Slower, and cooler for it. The radios need at least 80 MHz; nothing here
     needs more — the work between two packets is parsing a few dozen bytes of
     advertisement, and the rest is waiting.
  */
  setCpuFrequencyMhz(CPU_FREQUENCY_MHZ);

  statusLed.begin();
  ruuviTags.begin();
  setupScanning();

  // CONFIG_IDF_TARGET is the chip the core was built for: "esp32s3", "esp32c3".
  Serial.printf("ScrewCloud %s reader, device %s -> %s:%u, CPU %u MHz\n",
                CONFIG_IDF_TARGET, DEVICE_ID, SERVER_HOST, (unsigned)SERVER_PORT,
                (unsigned)getCpuFrequencyMhz());

  startListening();

  /*
     Started last, so that everything above is free to take as long as it needs.
     A watchdog armed before the radios were up would turn a slow boot into a
     boot loop — one that hides the console output explaining why the boot was
     slow.
  */
  startWatchdog();
}

void loop() {
  esp_task_wdt_reset();

  statusLed.update();

  switch (phase) {
    case Phase::Listening: {
      static unsigned long lastPrint = 0;
      if (millis() - lastPrint >= PRINT_INTERVAL_MS) {
        lastPrint = millis();
        printReadings();
      }
      if (millis() - phaseStartedAt >= LISTEN_MS) {
        startResting();
      }
      break;
    }
    case Phase::Resting:
#ifdef LIGHT_SLEEP_BETWEEN_SENDS
      sleepUntilNextCycle();
#endif
      if (millis() - phaseStartedAt >= restFor) {
        startListening();
      }
      break;
  }

  // Yield to the WiFi and BLE tasks. There is no run loop of ours to service.
  delay(10);
}
