/*
  ScrewCloud sensor firmware for ESP32-S3 — minimal variant.

  Reads RuuviTags over BLE and sends the readings to the server over the board's
  own WiFi. That is all: no DHT22, no display, no NB-IoT modem. For those, use
  the temperature-reader sketch on a Raspberry Pi Pico 2 W.

  Developed against a Waveshare ESP32-S3-Zero, which has no plain LED — status is
  shown on its WS2812 RGB LED instead, where the colour carries the meaning.

  Libraries: NimBLE-Arduino (2.x) from Library Manager. Nothing else; WiFi and
  neopixelWrite() come with the ESP32 Arduino core (3.x).

  Wire format and other details: see the project README.md
  RuuviTag data format:
  https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2
*/

#include <WiFi.h>
#include <WiFiUdp.h>
#include <NimBLEDevice.h>

#include "config.h"
#include "Protocol.h"

/* ==========================================================================
   RuuviTag Data Format 5

   Identical byte math to the Pico firmware. Only the plumbing around it differs.
   ========================================================================== */

// Ruuvi Innovations Ltd, Bluetooth SIG company identifier (little endian on the wire)
static const uint16_t RUUVI_COMPANY_ID = 0x0499;
static const uint8_t RUUVI_FORMAT_5 = 0x05;
static const uint8_t RUUVI_FORMAT_5_LEN = 24;  // bytes after the company id

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
   Only the fields this firmware actually reports. The Pico version also decodes
   pressure, acceleration and battery voltage for its serial log; here they are
   skipped to keep things minimal.
*/
struct RuuviReading {
  bool valid = false;
  uint8_t mac[6] = {0};
  float temperature = NAN;  // °C
  float humidity = NAN;     // %RH
  int rssi = 0;
  unsigned long receivedAt = 0;

  bool matches(const uint8_t otherMac[6]) const {
    return valid && memcmp(mac, otherMac, sizeof(mac)) == 0;
  }

  bool isStale() const {
    return millis() - receivedAt > RUUVI_STALE_MS;
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
    if (data.size() < 2 + RUUVI_FORMAT_5_LEN) {
      return;
    }

    const uint8_t *bytes = reinterpret_cast<const uint8_t *>(data.data());
    uint16_t companyId = bytes[0] | (bytes[1] << 8);  // little endian
    if (companyId != RUUVI_COMPANY_ID) {
      return;
    }

    RuuviReading reading;
    if (reading.parseFormat5(&bytes[2], data.size() - 2)) {
      reading.rssi = device->getRSSI();
      ruuviTags.store(reading);
    }
  }
};

static RuuviScanCallbacks scanCallbacks;

static void startScanning() {
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
  scan->start(0);  // 0 = scan forever
}

/* ==========================================================================
   Status LED

   The ESP32-S3-Zero has no plain LED, only a WS2812 on RGB_LED_PIN. The colour
   carries the state and the rhythm reinforces it, which reads better across a
   room than rhythm alone.
   ========================================================================== */

enum class LinkStatus : uint8_t { Unknown, Ok, Failed };

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
static LinkStatus linkStatus = LinkStatus::Unknown;

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
       is no run loop of ours to pump here.
    */
    delay(50);
  }
  Serial.printf("WiFi: connected, IP %s\n", WiFi.localIP().toString().c_str());
  return true;
}

/*
   Assembles and sends a measurement packet. Stale tags are left out: the last
   reading of a dead tag is not a measurement, and on the server it would look
   fresh.
*/
static void reportMeasurements() {
  static uint16_t sequence = 0;

  RuuviReading readings[MAX_RUUVI_TAGS];
  uint8_t count = ruuviTags.snapshot(readings, MAX_RUUVI_TAGS);

  MeasurementPacket packet;
  packet.begin(DEVICE_ID, sequence + 1);

  SensorReading reading;
  for (uint8_t i = 0; i < count; i++) {
    if (readings[i].isStale()) {
      continue;
    }
    readings[i].fillReading(reading);
    packet.add(reading);
  }

  if (packet.sensorCount() == 0) {
    Serial.println("Send skipped: no fresh tag readings");
    return;
  }

  sequence++;
  bool sent = false;
  if (connectWiFi() && udp.beginPacket(SERVER_HOST, SERVER_PORT) == 1) {
    udp.write(packet.data(), packet.size());
    sent = udp.endPacket() == 1;
  }

  linkStatus = sent ? LinkStatus::Ok : LinkStatus::Failed;
  statusLed.setStatus(linkStatus);

  Serial.printf("Send %s: %u sensors, %u bytes, sequence %u\n",
                sent ? "ok" : "FAILED", packet.sensorCount(), packet.size(), sequence);
}

static void printReadings() {
  RuuviReading readings[MAX_RUUVI_TAGS];
  uint8_t count = ruuviTags.snapshot(readings, MAX_RUUVI_TAGS);

  if (count == 0) {
    Serial.println("No RuuviTags heard yet");
    return;
  }
  for (uint8_t i = 0; i < count; i++) {
    const RuuviReading &tag = readings[i];
    char id[PROTOCOL_ID_SIZE + 1];
    tag.sensorIdTo(id, sizeof(id));
    const char *name = ruuviNameFor(tag.mac);
    Serial.printf("%s%s%s %02X:%02X:%02X:%02X:%02X:%02X  %.2f C  %.2f %%  RSSI %d dBm  (%lu ms ago)%s\n",
                  id, name != nullptr ? " / " : "", name != nullptr ? name : "",
                  tag.mac[0], tag.mac[1], tag.mac[2], tag.mac[3], tag.mac[4], tag.mac[5],
                  tag.temperature, tag.humidity, tag.rssi,
                  millis() - tag.receivedAt, tag.isStale() ? "  [STALE]" : "");
  }
  if (ruuviTags.droppedCount() > 0) {
    Serial.printf("%lu advertisements dropped, MAX_RUUVI_TAGS (%u) full\n",
                  ruuviTags.droppedCount(), MAX_RUUVI_TAGS);
  }
}

void setup() {
  Serial.begin(115200);

  statusLed.begin();
  ruuviTags.begin();
  startScanning();

  // Connecting here is only for fast feedback; a failure is not fatal since
  // reportMeasurements() reconnects as needed.
  if (!connectWiFi()) {
    linkStatus = LinkStatus::Failed;
    statusLed.setStatus(linkStatus);
  }

  Serial.printf("ScrewCloud ESP32-S3 reader, device %s -> %s:%u\n",
                DEVICE_ID, SERVER_HOST, (unsigned)SERVER_PORT);
}

void loop() {
  statusLed.update();

  static unsigned long lastPrint = 0;
  if (millis() - lastPrint >= PRINT_INTERVAL_MS) {
    lastPrint = millis();
    printReadings();
  }

  static unsigned long lastSend = 0;
  static bool firstSendDone = false;
  unsigned long sendInterval;
  if (!firstSendDone) {
    sendInterval = FIRST_SEND_DELAY_MS;
  } else if (linkStatus == LinkStatus::Failed) {
    sendInterval = RETRY_INTERVAL_MS;
  } else {
    sendInterval = SEND_INTERVAL_MS;
  }
  if (millis() - lastSend >= sendInterval) {
    lastSend = millis();
    firstSendDone = true;
    reportMeasurements();
  }

  // Yield to the WiFi and BLE tasks. There is no run loop of ours to service.
  delay(10);
}
