/*
  ScrewCloud sensor firmware for Raspberry Pi Pico 2 W.

  Reads temperatures from up to three sources:
    1. RuuviTags over BLE advertisements (Data Format 5 / RAWv2), several supported
    2. A DHT22 wired to GP15
    3. The RP2350 internal temperature sensor (the chip's own die temperature)

  The DHT22, the OLED display and the NB-IoT modem are all optional. The minimum
  setup is a bare Pico 2 W with a RuuviTag in range: set DEVICE_ID and the WiFi
  credentials in config.h and nothing else.

  How each one is optional:
    - DHT22:   detected at runtime. After a few failed reads it is declared
               absent, stops being read, and is left out of the display and the
               packet. Its display row goes to the tags instead.
    - OLED:    nothing to detect — 4-wire SPI has no read-back, so the sketch
               cannot tell whether a display is attached and simply draws into
               the void when it is not. Nothing fails either way.
    - NB-IoT:  probed with an AT command at boot. If the modem does not answer,
               WiFi is used instead (TRANSPORT_AUTO).
    - RuuviTag: any number from zero upwards. With no sensors at all the sketch
               skips sending rather than transmitting an empty packet.

  Requires in Arduino IDE: Tools -> IP/Bluetooth Stack -> "IPv4 + Bluetooth".

  Libraries: Adafruit DHT sensor library, Adafruit Unified Sensor,
  Adafruit SH110X, Adafruit GFX Library.

  Wiring and other details: see the project README.md
  RuuviTag data format:
  https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2
*/

#include <BTstackLib.h>
#include <DHT.h>
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SH110X.h>

#include "config.h"
#include "Protocol.h"
#include "Transport.h"

#ifdef USE_WIFI
static WiFiTransport wifiTransport;
#endif
#ifdef USE_NBIOT
static Sim7028Transport nbiotTransport;
#endif

/*
   Chosen in setup() so that TRANSPORT_AUTO can detect the modem at runtime.
   A pointer rather than a reference because the value is not known at compile
   time.
*/
static Transport *transport = nullptr;

/*
   TRANSPORT_AUTO: use the NB-IoT modem if it answers an AT command, otherwise
   WiFi. The same binary then works both on a bare Pico and with the modem
   attached, with no configuration.
*/
static Transport *selectTransport() {
#if defined(TRANSPORT_AUTO)
  if (nbiotTransport.probe()) {
    return &nbiotTransport;
  }
  Serial.println("No NB-IoT modem found, falling back to WiFi");
  return &wifiTransport;
#elif defined(TRANSPORT_WIFI)
  return &wifiTransport;
#else
  return &nbiotTransport;
#endif
}

/*
   Transport.h calls this inside its wait loops so that BLE advertisements keep
   being received while a network connection is being established.
*/
void transportIdle() {
  BTstack.loop();
}

// Ruuvi Innovations Ltd, Bluetooth SIG company identifier (little endian on the wire)
static const uint16_t RUUVI_COMPANY_ID = 0x0499;
static const uint8_t RUUVI_FORMAT_5 = 0x05;
static const uint8_t RUUVI_FORMAT_5_LEN = 24;  // bytes after the company id

// BLE AD types
static const uint8_t AD_TYPE_MANUFACTURER_SPECIFIC = 0xFF;

// DHT22 data line. GP15 = physical pin 20, no default peripheral function.
static const uint8_t DHT_DATA_PIN = 15;

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
   Waveshare 1.3" OLED (B), SH1106, 128x64, 4-wire SPI.
   DIN and CLK go to the SPI0 default pins (GP19 / GP18), so the SPI object
   needs no pin configuration. CS, DC and RST are freely choosable and were
   picked from adjacent pins to keep the wiring tidy.
*/
static const uint8_t OLED_CS_PIN = 17;
static const uint8_t OLED_DC_PIN = 20;
static const uint8_t OLED_RST_PIN = 21;
static const uint8_t OLED_WIDTH = 128;
static const uint8_t OLED_HEIGHT = 64;

/*
   Display layout. The Adafruit_GFX font is 6x8 px at text size 1, so size 2 is
   12x16 px and size 1 is 6x8 px.

   Three rows 20 pixels apart: the temperature large and right aligned, the name
   of the measuring point small on the left. Only 10 characters fit per row at
   size 2, so something has to give, and the name is the part a reader can still
   read at half the size — a temperature is meant to be legible across a room.

   The bottom 8 pixels are reserved for the link status line in the small font.
   3 x 20 = 60 pixels for rows, the remaining 4 px for spacing and the status.

       cold room     24.9
       R2            -3.2
       DHT           25.6
       Sent ok, 45 s
*/
static const uint8_t OLED_ROWS = 3;
static const uint8_t OLED_ROW_HEIGHT = 20;
static const uint8_t OLED_ROW_TOP = 0;
static const uint8_t OLED_STATUS_TOP = 56;
static const uint8_t OLED_BIG_CHAR_WIDTH = 12;
static const uint8_t OLED_SMALL_CHAR_WIDTH = 6;

// Longest name a row can show: the display fits about a dozen next to a reading.
static const uint8_t OLED_NAME_SIZE = 16;

static const unsigned long PRINT_INTERVAL_MS = 5000;

// The DHT22 does not allow reads more often than every 2 s.
static const unsigned long DHT_MIN_INTERVAL_MS = 2000;

// How many distinct RuuviTags are kept in memory at once.
static const uint8_t MAX_RUUVI_TAGS = 8;

// A RuuviTag broadcasts every ~1285 ms, so anything older than this is suspect.
static const unsigned long RUUVI_STALE_MS = 60000;

// Ruuvi fields are big endian.
static int16_t readInt16(const uint8_t *p) {
  return (int16_t)((p[0] << 8) | p[1]);
}

static uint16_t readUint16(const uint8_t *p) {
  return (uint16_t)((p[0] << 8) | p[1]);
}

/*
   Human readable names for tags, for the serial log and the OLED. Naming a tag
   is optional: an unnamed one is shown by a running number instead.

   These names are for the reader here, and go nowhere else. What the server
   ties a reading to is the identifier derived from the MAC (sensorIdTo), which
   no entry here can change — so renaming a measuring point is a display
   change, never a new sensor in the database. The proper, long name belongs in
   the web interface anyway; this is what fits on a 128 pixel display.

   Add your own tags here — the MAC is printed on the serial console. Around a
   dozen characters is what the display has room for.
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

struct RuuviMeasurement {
  bool valid = false;
  float temperature = NAN;  // °C
  float humidity = NAN;     // %RH
  float pressure = NAN;     // hPa
  float accelerationX = NAN;  // g
  float accelerationY = NAN;
  float accelerationZ = NAN;
  float batteryVoltage = NAN;  // V
  int txPower = 0;             // dBm
  uint8_t movementCounter = 0;
  uint16_t sequenceNumber = 0;
  uint8_t mac[6] = {0};
  int rssi = 0;
  unsigned long receivedAt = 0;

  bool matches(const uint8_t otherMac[6]) const {
    return valid && memcmp(mac, otherMac, sizeof(mac)) == 0;
  }

  bool isStale() const {
    return millis() - receivedAt > RUUVI_STALE_MS;
  }

  /*
     The identifier the server ties readings to. It has to mean the same tag
     after a reboot and a different tag from every neighbour, so it is derived
     from the MAC and from nothing else — not from RUUVI_NAMES, which a reader
     may edit freely without splitting a sensor's history in two.

     The low twelve bits of the address: four thousand values, where the last
     byte alone gave 256 and two tags in eight collided about one time in ten.
     It fills the four bytes the protocol has for an identifier exactly.

     Nobody has to read this. What a reader sees is displayNameTo.
  */
  void sensorIdTo(char *buffer, size_t size) const {
    snprintf(buffer, size, "R%03X", ((mac[4] & 0x0F) << 8) | mac[5]);
  }

  /*
     What a reader sees: the name from RUUVI_NAMES, or a running number in the
     order the tags were first heard.

     The number is the tag's place in the registry, which is that order — a tag
     holds its slot for as long as the device is up, so R2 stays R2 and does not
     move under somebody reading the display. It does change across a reboot, if
     the tags happen to be heard in another order. That is the price of a number
     short enough to fit; a name from the table above is not affected.
  */
  void displayNameTo(char *buffer, size_t size, uint8_t ordinal) const {
    const char *name = ruuviNameFor(mac);
    if (name != nullptr) {
      snprintf(buffer, size, "%s", name);
    } else {
      snprintf(buffer, size, "R%u", ordinal);
    }
  }

  void fillReading(SensorReading &reading) const {
    sensorIdTo(reading.id, sizeof(reading.id));
    reading.temperature = temperature;
    reading.humidity = humidity;
  }

  /*
     Fills this measurement from a Ruuvi Data Format 5 packet. data points at
     the format byte (0x05) and len is the number of bytes remaining. Fields
     carrying their "invalid" value are left as NaN / zero.

     A member function rather than a free function on purpose. The Arduino IDE
     generates prototypes for free functions and places them after the includes,
     that is before the sketch's own type definitions — a RuuviMeasurement
     parameter would fail with "has not been declared". Member functions get no
     generated prototype.
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

    uint16_t rawPressure = readUint16(&data[5]);
    if (rawPressure != 0xFFFF) {
      pressure = (rawPressure + 50000.0f) / 100.0f;  // Pa -> hPa
    }

    int16_t rawAccX = readInt16(&data[7]);
    int16_t rawAccY = readInt16(&data[9]);
    int16_t rawAccZ = readInt16(&data[11]);
    if (rawAccX != (int16_t)0x8000) {
      accelerationX = rawAccX / 1000.0f;
    }
    if (rawAccY != (int16_t)0x8000) {
      accelerationY = rawAccY / 1000.0f;
    }
    if (rawAccZ != (int16_t)0x8000) {
      accelerationZ = rawAccZ / 1000.0f;
    }

    uint16_t powerInfo = readUint16(&data[13]);
    if (powerInfo != 0xFFFF) {
      uint16_t batteryMilliVolts = powerInfo >> 5;  // top 11 bits
      uint8_t txPowerSteps = powerInfo & 0x1F;      // bottom 5 bits
      if (batteryMilliVolts != 0x7FF) {
        batteryVoltage = (1600.0f + batteryMilliVolts) / 1000.0f;
      }
      if (txPowerSteps != 0x1F) {
        txPower = -40 + (txPowerSteps * 2);
      }
    }

    movementCounter = data[15];
    sequenceNumber = readUint16(&data[16]);
    memcpy(mac, &data[18], sizeof(mac));

    valid = true;
    receivedAt = millis();
    return true;
  }

  void printTo(Print &out) const {
    const char *name = ruuviNameFor(mac);
    out.printf("RuuviTag %s%s%02X:%02X:%02X:%02X:%02X:%02X  RSSI %d dBm  (%lu ms ago)%s\n",
               name != nullptr ? name : "", name != nullptr ? " / " : "",
               mac[0], mac[1], mac[2], mac[3], mac[4], mac[5],
               rssi, millis() - receivedAt,
               isStale() ? "  [STALE]" : "");
    out.printf("  temperature %.2f C, humidity %.2f %%, pressure %.2f hPa\n",
               temperature, humidity, pressure);
    out.printf("  battery %.3f V, TX %d dBm, movement %u, sequence %u\n",
               batteryVoltage, txPower, movementCounter, sequenceNumber);
  }
};

/*
   Keeps track of every tag heard, keyed by MAC address. A fixed array because
   dynamic allocation in a sketch that runs for months is a needless risk. If
   there are more tags than slots the extras are ignored and that is reported —
   no silent data loss.
*/
struct RuuviRegistry {
  RuuviMeasurement tags[MAX_RUUVI_TAGS];
  unsigned long dropped = 0;

  void store(const RuuviMeasurement &measurement) {
    for (RuuviMeasurement &tag : tags) {
      if (tag.matches(measurement.mac)) {
        tag = measurement;
        return;
      }
    }
    for (RuuviMeasurement &tag : tags) {
      if (!tag.valid) {
        tag = measurement;
        return;
      }
    }
    dropped++;
  }

  void printTo(Print &out) const {
    uint8_t count = 0;
    for (const RuuviMeasurement &tag : tags) {
      if (tag.valid) {
        tag.printTo(out);
        count++;
      }
    }
    if (count == 0) {
      out.println("RuuviTag: nothing heard yet");
    }
    if (dropped > 0) {
      out.printf("RuuviTag: %lu advertisements dropped, MAX_RUUVI_TAGS (%u) full\n",
                 dropped, MAX_RUUVI_TAGS);
    }
  }
};

static RuuviRegistry ruuviTags;

/*
   Whether a sensor is there at all. A missing sensor is not worth reading every
   round, because the Adafruit library's read keeps interrupts disabled for a
   couple of milliseconds and fails anyway.
*/
enum class SensorPresence : uint8_t { Unknown, Present, Absent };

struct DhtSensor {
  DHT dht{DHT_DATA_PIN, DHT22};
  float temperature = NAN;  // °C
  float humidity = NAN;     // %RH
  unsigned long readAt = 0;
  unsigned long failures = 0;
  SensorPresence presence = SensorPresence::Unknown;

  void begin() {
#ifdef ENABLE_DHT22
    dht.begin();
#else
    presence = SensorPresence::Absent;
#endif
  }

  /** Whether there is a reading worth showing and sending. */
  bool hasReading() const {
    return presence == SensorPresence::Present && !isnan(temperature);
  }

  /*
     The Adafruit library bit-bangs the protocol and keeps interrupts disabled
     for a few milliseconds, so this is called sparingly rather than on every
     loop() iteration.
  */
  void update() {
    if (presence == SensorPresence::Absent) {
      return;
    }
    if (readAt != 0 && millis() - readAt < DHT_MIN_INTERVAL_MS) {
      return;
    }

    float newHumidity = dht.readHumidity();
    float newTemperature = dht.readTemperature();
    readAt = millis();

    if (isnan(newHumidity) || isnan(newTemperature)) {
      failures++;
      /*
         The sensor is only declared missing if it has never worked. One that
         has produced a reading keeps being retried forever, because then the
         problem is interference rather than absent hardware.
      */
      if (presence == SensorPresence::Unknown && failures >= DHT_FAILURES_BEFORE_ABSENT) {
        presence = SensorPresence::Absent;
        Serial.println("DHT22: no response, leaving it out. If a sensor is wired,");
        Serial.println("  check the data line on GP15 and the 3.3 V supply.");
      }
      return;
    }

    presence = SensorPresence::Present;
    humidity = newHumidity;
    temperature = newTemperature;
  }

  void fillReading(SensorReading &reading) const {
    snprintf(reading.id, sizeof(reading.id), "DHT");
    reading.temperature = temperature;
    reading.humidity = humidity;
  }

  void printTo(Print &out) const {
    if (presence == SensorPresence::Absent) {
      out.println("DHT22: not connected");
      return;
    }
    if (isnan(temperature)) {
      out.printf("DHT22: no reading yet (%lu failed reads)\n", failures);
      return;
    }
    out.printf("DHT22: temperature %.1f C, humidity %.1f %% (%lu failed reads)\n",
               temperature, humidity, failures);
  }
};

static DhtSensor dhtSensor;

enum class LinkStatus : uint8_t { Unknown, Ok, Failed };

/*
   Result of the most recent send. Both the LED and the display read this, so
   they cannot end up telling different stories.
*/
struct LinkState {
  LinkStatus status = LinkStatus::Unknown;
  unsigned long lastSuccessAt = 0;
  bool everSucceeded = false;

  void recordResult(bool ok) {
    status = ok ? LinkStatus::Ok : LinkStatus::Failed;
    if (ok) {
      lastSuccessAt = millis();
      everSucceeded = true;
    }
  }
};

static LinkState linkState;

/*
   LED blink patterns. Each table holds alternating durations in milliseconds
   starting with an on period, so even indices are lit.

   The rhythms were chosen to be told apart at a glance without counting:
     ok       two short flashes and a long dark pause
     failed   fast steady pulsing
     unknown  calm steady blinking
*/
static const uint16_t BLINK_STEPS_OK[] = {80, 200, 80, 2600};
static const uint16_t BLINK_STEPS_FAILED[] = {100, 100};
static const uint16_t BLINK_STEPS_UNKNOWN[] = {500, 500};

struct StatusLed {
  void begin() {
    pinMode(LED_BUILTIN, OUTPUT);
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
    if (millis() - stepStartedAt < currentStepDuration()) {
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

  uint16_t currentStepDuration() const {
    return steps()[step];
  }

  void applyStep() {
    digitalWrite(LED_BUILTIN, (step % 2 == 0) ? HIGH : LOW);
  }
};

static StatusLed statusLed;

/*
   The RP2350's own die temperature.

   This is a diagnostic sensor, not a thermometer: it sits on the same silicon as
   the CPU and the radio, so it reads well above the room and rises further the
   busier the chip is. It is included because watching it is interesting — the
   shape of the curve says something about the load and the enclosure — not
   because the number means anything about the air.

   It needs no hardware and cannot fail, so unlike the DHT22 there is nothing to
   detect: if it is enabled, it reports.
*/
struct InternalTemperature {
  float temperature = NAN;  // °C

  void update() {
#ifdef ENABLE_INTERNAL_TEMPERATURE
    temperature = analogReadTemp();
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

/*
   The OLED is for reading in the room. It shows the room's temperatures and
   humidities only — the chip's own temperature and the diagnostics belong on the
   serial console and, in the chip's case, in the web UI where it can be watched
   over time. Three rows is not much, and none of them should go to a number that
   says nothing about the room.

   With more sensors than rows, the tags win: they are where somebody wanted a
   temperature measured, and the DHT22 is wherever the box ended up.

   The display is entirely optional. Nothing here fails if it is absent; SPI has
   no read-back, so the sketch simply draws into the void.
*/
struct Display {
  Adafruit_SH1106G oled{OLED_WIDTH, OLED_HEIGHT, &SPI, OLED_DC_PIN, OLED_RST_PIN, OLED_CS_PIN};
  bool ready = false;

  void begin() {
    ready = oled.begin();
    if (!ready) {
      Serial.println("OLED: init failed, continuing without a display");
      return;
    }
    oled.setTextColor(SH110X_WHITE);
    oled.setTextWrap(false);  // rows are aligned by hand, wrapping would only interfere
    oled.clearDisplay();
    oled.display();
  }

  void render(const DhtSensor &dht, const RuuviRegistry &registry, const LinkState &link) {
    if (!ready) {
      return;
    }
    oled.clearDisplay();

    uint8_t row = 0;

    /*
       The tags first: they are the measuring points somebody put where they
       wanted a temperature, while the DHT22 is wherever this box happens to sit.
       With more sensors than rows, that is the one to lose.

       Tags that have gone quiet come last of all, after the DHT22. They are worth
       showing — a reading from a minute ago still says something — but a tag that
       has stopped sending must never push a sensor that is still reporting off
       the screen.
    */
    row = printTags(registry, row, false);

    if (row < OLED_ROWS && dht.hasReading()) {
      printRow(row++, "DHT", dht.temperature);
    }

    row = printTags(registry, row, true);

    if (row == 0) {
      oled.setTextSize(1);
      oled.setCursor(0, OLED_ROW_TOP);
      oled.print("Waiting for sensors");
    }

    printStatus(link);
    oled.display();
  }

private:
  /*
     The tags that are still reporting, or the ones that have gone quiet, in the
     rows that are left.
  */
  uint8_t printTags(const RuuviRegistry &registry, uint8_t row, bool stale) {
    for (uint8_t i = 0; i < MAX_RUUVI_TAGS; i++) {
      const RuuviMeasurement &tag = registry.tags[i];
      if (!tag.valid || tag.isStale() != stale) {
        continue;
      }
      if (row >= OLED_ROWS) {
        break;
      }
      // The slot number, not the row: a tag keeps its name when another one
      // above it goes quiet and moves down the screen.
      char name[OLED_NAME_SIZE + 1];
      tag.displayNameTo(name, sizeof(name), i + 1);
      printRow(row++, name, tag.temperature);
    }
    return row;
  }

  /*
     Temperature right aligned in the large font, name on the left in the small
     one, vertically centred against it.

     The name is an identifier and the temperature is the measurement, so the
     large font goes to the temperature. Humidity does not get the row at all:
     on a display glanced at while walking past, which measuring point and how
     warm it is are what earn the space, and everything else is a tap away on
     the dashboard.
  */
  void printRow(uint8_t row, const char *name, float temperature) {
    const int16_t top = OLED_ROW_TOP + row * OLED_ROW_HEIGHT;
    char text[8];

    if (isnan(temperature)) {
      snprintf(text, sizeof(text), "--");
    } else {
      snprintf(text, sizeof(text), "%.1f", temperature);
    }
    const int16_t width = strlen(text) * OLED_BIG_CHAR_WIDTH;

    oled.setTextSize(2);
    oled.setCursor(OLED_WIDTH - width, top);
    oled.print(text);

    /*
       Whatever the reading leaves, less a space. Measured against the reading
       actually being drawn rather than a fixed column, so a name gets the three
       extra characters that -12.3 would have taken. Truncated rather than
       wrapped or shrunk: a name running into the reading is worse than a name
       that stops early.
    */
    const uint8_t fits = (OLED_WIDTH - width) / OLED_SMALL_CHAR_WIDTH - 1;
    char shown[OLED_NAME_SIZE + 1];
    snprintf(shown, sizeof(shown), "%.*s", (int)fits, name);

    oled.setTextSize(1);
    oled.setCursor(0, top + 4);
    oled.print(shown);
  }

  /*
     Link status on the bottom row. On failure it shows the time since the last
     successful send, which says more than the time of the failed attempt.
  */
  void printStatus(const LinkState &link) {
    char text[24];
    switch (link.status) {
      case LinkStatus::Ok:
        snprintf(text, sizeof(text), "Sent ok, %s", elapsed(link.lastSuccessAt));
        break;
      case LinkStatus::Failed:
        if (link.everSucceeded) {
          snprintf(text, sizeof(text), "FAIL! last ok %s", elapsed(link.lastSuccessAt));
        } else {
          snprintf(text, sizeof(text), "FAIL! no connection");
        }
        break;
      default:
        snprintf(text, sizeof(text), "Not sent yet");
        break;
    }
    oled.setTextSize(1);
    oled.setCursor(0, OLED_STATUS_TOP);
    oled.print(text);
  }

  /*
     The unit changes so the text stays short — a row holds 21 characters and
     the exact minute count of a long outage is not interesting.
  */
  static const char *elapsed(unsigned long since) {
    static char text[10];
    unsigned long seconds = (millis() - since) / 1000;
    if (seconds < 100) {
      snprintf(text, sizeof(text), "%lu s", seconds);
    } else if (seconds < 100UL * 60UL) {
      snprintf(text, sizeof(text), "%lu min", seconds / 60);
    } else {
      snprintf(text, sizeof(text), "%lu h", seconds / 3600);
    }
    return text;
  }
};

static Display display;

/*
   Finds the manufacturer specific field (AD type 0xFF) in advertisement data.

   BLEAdvertisement always copies a full 31 bytes and does not expose the real
   length, so the AD structures are walked until a zero length appears or the
   buffer runs out.
*/
static const uint8_t *findManufacturerData(const uint8_t *adv, uint8_t &payloadLen) {
  for (uint8_t i = 0; i + 1 < LE_ADVERTISING_DATA_SIZE;) {
    uint8_t fieldLen = adv[i];
    if (fieldLen == 0 || i + 1 + fieldLen > LE_ADVERTISING_DATA_SIZE) {
      break;
    }
    if (adv[i + 1] == AD_TYPE_MANUFACTURER_SPECIFIC) {
      payloadLen = fieldLen - 1;  // drop the type byte
      return &adv[i + 2];
    }
    i += 1 + fieldLen;
  }
  payloadLen = 0;
  return nullptr;
}

static void advertisementCallback(BLEAdvertisement *advertisement) {
  uint8_t payloadLen = 0;
  const uint8_t *manufacturerData = findManufacturerData(advertisement->getAdvData(), payloadLen);
  if (manufacturerData == nullptr || payloadLen < 2) {
    return;
  }

  uint16_t companyId = manufacturerData[0] | (manufacturerData[1] << 8);  // little endian
  if (companyId != RUUVI_COMPANY_ID) {
    return;
  }

  RuuviMeasurement measurement;
  if (measurement.parseFormat5(&manufacturerData[2], payloadLen - 2)) {
    measurement.rssi = advertisement->getRssi();
    ruuviTags.store(measurement);
  }
}

/*
   Assembles and sends a measurement packet. Stale RuuviTags are left out: the
   last reading of a dead tag is not a measurement, and on the server it would
   look fresh.
*/
static void reportMeasurements() {
  static uint16_t sequence = 0;

  MeasurementPacket packet;
  packet.begin(DEVICE_ID, ++sequence);

  SensorReading reading;
  if (dhtSensor.hasReading()) {
    dhtSensor.fillReading(reading);
    packet.add(reading);
  }

  if (internalTemperature.hasReading()) {
    internalTemperature.fillReading(reading);
    packet.add(reading);
  }

  for (const RuuviMeasurement &tag : ruuviTags.tags) {
    if (!tag.valid || tag.isStale()) {
      continue;
    }
    tag.fillReading(reading);
    packet.add(reading);
  }

  // Nothing to report yet; no point spending a transmission on an empty packet.
  if (packet.sensorCount() == 0) {
    Serial.println("Send skipped: no sensor readings yet");
    return;
  }

  bool sent = transport->send(packet.data(), packet.size());
  linkState.recordResult(sent);
  statusLed.setStatus(linkState.status);

  Serial.printf("Send %s: %u sensors, %u bytes, sequence %u\n",
                sent ? "ok" : "FAILED",
                packet.sensorCount(), packet.size(), sequence);

  // Update the display immediately so the status is not one cycle behind.
  display.render(dhtSensor, ruuviTags, linkState);
}

void setup() {
  Serial.begin(115200);

  statusLed.begin();
  dhtSensor.begin();
  display.begin();

  BTstack.setBLEAdvertisementCallback(advertisementCallback);
  BTstack.setup();

  /*
     BTstack defaults to a 30 ms window every 300 ms, which misses most of a
     RuuviTag's broadcasts since it only transmits every ~1285 ms. Scan
     passively and effectively continuously (unit is 0.625 ms).
  */
  gap_set_scan_params(0 /* passive */, 0x0030, 0x0030, 0 /* all devices */);
  BTstack.bleStartScanning();

  /*
     Last, because NB-IoT registration can take minutes. transportIdle() keeps
     BTstack running while it waits, so scanning is already collecting
     advertisements.
  */
  transport = selectTransport();
  Serial.printf("Transport: %s\n", transport->name());
  if (!transport->begin()) {
    Serial.println("Transport init failed, will retry when sending");
    // Flag the failure right away so a wrong WiFi password shows up on the LED
    // and the display instead of only after the first send attempt.
    linkState.recordResult(false);
    statusLed.setStatus(linkState.status);
  }
}

void loop() {
  // BTstack's run loop needs to spin often, so no delay() anywhere.
  BTstack.loop();

  statusLed.update();

  static unsigned long lastPrint = 0;
  if (millis() - lastPrint >= PRINT_INTERVAL_MS) {
    lastPrint = millis();
    dhtSensor.update();
    internalTemperature.update();
    internalTemperature.printTo(Serial);
    dhtSensor.printTo(Serial);
    ruuviTags.printTo(Serial);
    display.render(dhtSensor, ruuviTags, linkState);
  }

  // The first send happens soon after boot so that a working connection is
  // visible immediately rather than one send interval later.
  static unsigned long lastSend = 0;
  static bool firstSendDone = false;
  unsigned long sendInterval;
  if (!firstSendDone) {
    sendInterval = FIRST_SEND_DELAY_MS;
  } else if (linkState.status == LinkStatus::Failed) {
    sendInterval = RETRY_INTERVAL_MS;
  } else {
    sendInterval = SEND_INTERVAL_MS;
  }
  if (millis() - lastSend >= sendInterval) {
    lastSend = millis();
    firstSendDone = true;
    reportMeasurements();
  }
}
