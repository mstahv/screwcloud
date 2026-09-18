#pragma once

#include <Arduino.h>
#include <math.h>

/*
  Binary wire format of a measurement packet.

  Big endian, in the same spirit as Ruuvi's own formats. The goal is a small
  packet, because NB-IoT traffic is metered.

  Header, 8 bytes:
    0     version     uint8, currently 2
    1..4  deviceId    4 x ASCII, space padded
    5     count       uint8, number of sensors
    6..7  sequence    uint16, increments per packet and wraps

  Sensor record, 5 bytes plus its fields, repeated count times:
    0..3  id          4 x ASCII, space padded
    4     fields      uint8, number of fields that follow
    then per field, 3 bytes:
      0   type        uint8, from the registry below
      1..2 value      uint16 or int16 depending on the type

  A plain RuuviTag sends four fields — temperature, humidity, pressure and its
  battery — and costs 17 bytes; a Ruuvi Air sends eight and costs 29. The
  largest packet this can build is 8 x (5 + 9 x 3) + 8 = 264 bytes, well inside
  the ~508 bytes a UDP datagram carries safely.

  WHY THIS SHAPE, AND WHY VERSION 2

  Version 1 was a fixed 8-byte sensor record: temperature and humidity, with
  sentinel values for "missing". It had no room for a third reading, which is
  what the Ruuvi Air wanted — see protocol-evolution.md, which worked through
  the alternatives and recommended exactly this one.

  Two things follow from type-length-value that are worth stating, because both
  are easy to undo by accident:

  1. A MISSING FIELD IS NOT SENT. Version 1 needed sentinels because a fixed
     record cannot leave anything out; here absence is the natural encoding, and
     so the sentinel constants are gone. A reading that would not survive the
     round trip — out of range, or not measured — is simply omitted, and the
     receiver reports null for it exactly as before.

  2. AN UNKNOWN TYPE IS SKIPPED, NOT AN ERROR. Every field is the same three
     bytes, so a receiver that has never heard of a type steps over it and
     carries on. That is what lets a new measurement reach devices and servers
     on their own schedules instead of in one coordinated release.

  Version 1 devices keep sending version 1 forever and are none the wiser: the
  version byte is the first thing a receiver reads, and the server still decodes
  both. That was the whole point of having it.
*/

static const uint8_t PROTOCOL_VERSION = 2;
static const uint8_t PROTOCOL_HEADER_SIZE = 8;
static const uint8_t PROTOCOL_ID_SIZE = 4;
static const uint8_t PROTOCOL_MAX_SENSORS = 8;

/*
   Sensor record: the identifier and the field count that precede the fields,
   so PROTOCOL_ID_SIZE + 1. Written out as a number rather than as that sum
   because the readers' sync tests read these values straight out of this file,
   and they compare literals — a constant defined as an expression is a constant
   they quietly stop checking.
*/
static const uint8_t PROTOCOL_SENSOR_HEADER_SIZE = 5;

/* One field: a type byte and a 16-bit value. */
static const uint8_t PROTOCOL_FIELD_SIZE = 3;
/*
   As many as there are types, so that a sensor with all of them — a Ruuvi Air
   sends eight — is never turned away at the door. Seven was the number before
   the reserved types were put to use.
*/
static const uint8_t PROTOCOL_MAX_FIELDS = 9;

/*
   The field type registry.

   Numbers are permanent: a type means the same thing and carries the same
   scaling forever, in every firmware and every reader. Adding a measurement
   means taking the next free number here, never reusing or redefining one — a
   receiver that has not been updated must be able to skip what it does not know
   without being wrong about what it does.

   Three of these were reserved for a while — decoded by some readers, sent by
   none, with no column on the server. Reserving the numbers is what made
   putting them to use a matter of filling in the blanks rather than
   renumbering anything.
*/
static const uint8_t PROTOCOL_FIELD_TEMPERATURE = 1;  // int16,  0.01 °C
static const uint8_t PROTOCOL_FIELD_HUMIDITY = 2;     // uint16, 0.01 %RH
static const uint8_t PROTOCOL_FIELD_PRESSURE = 3;     // uint16, 0.1 hPa
static const uint8_t PROTOCOL_FIELD_CO2 = 4;          // uint16, ppm
static const uint8_t PROTOCOL_FIELD_PM25 = 5;         // uint16, 0.1 µg/m³
static const uint8_t PROTOCOL_FIELD_VOC = 6;          // uint16, index, Ruuvi's 0–500 scale
static const uint8_t PROTOCOL_FIELD_NOX = 7;          // uint16, index, Ruuvi's 0–500 scale
/*
   The sensor's own battery, so that a tag running down is seen on the server as
   a falling number rather than, one day, as silence. Millivolts: a coin cell
   reads about three volts new and is finished near two, so a hundredth would
   have done — but a thousandth costs nothing in sixteen bits, and it is the
   unit the tag itself reports in.
*/
static const uint8_t PROTOCOL_FIELD_BATTERY = 8;      // uint16, mV
/*
   Light, in whole lux. A Ruuvi Air measures it on a logarithmic scale that is
   coarse in the bright and fine in the dark, which is the right way round for
   telling a lit room from a dark one; the decoded lux are sent as they are.
*/
static const uint8_t PROTOCOL_FIELD_LUMINOSITY = 9;   // uint16, lx

/*
   A sensor-agnostic reading. Sensor classes fill this in, which keeps the
   packing logic from knowing anything about the DHT22 or a RuuviTag.

   NAN means "not measured", and a NAN never reaches the wire: the packer leaves
   the field out. A device that has no air sensor therefore costs nothing for
   the fields it does not have, which is the main thing this format buys over
   version 1.

   id is at most 3 characters, because the same identifier is drawn on the OLED
   in the large font where nothing longer fits.
*/
struct SensorReading {
  char id[PROTOCOL_ID_SIZE + 1] = "";
  float temperature = NAN;    // °C
  float humidity = NAN;       // %RH
  float co2 = NAN;            // ppm
  float pm25 = NAN;           // µg/m³
  float batteryVoltage = NAN; // V, the sensor's own battery
  float pressure = NAN;       // hPa
  float voc = NAN;            // VOC index, 0–500
  float nox = NAN;            // NOx index, 0–500
  float luminosity = NAN;     // lx
};

class MeasurementPacket {
public:
  void begin(const char *deviceId, uint16_t sequence) {
    memset(buffer, 0, sizeof(buffer));
    buffer[0] = PROTOCOL_VERSION;
    for (uint8_t i = 0; i < PROTOCOL_ID_SIZE; i++) {
      buffer[1 + i] = deviceId[i] != '\0' ? deviceId[i] : ' ';
    }
    buffer[5] = 0;
    writeUint16(&buffer[6], sequence);
    length = PROTOCOL_HEADER_SIZE;
  }

  bool add(const SensorReading &reading) {
    if (buffer[5] >= PROTOCOL_MAX_SENSORS) {
      return false;
    }

    /*
       The identifier and the field count go down first, then the fields, and
       the count is filled in as each one is written. A record whose fields
       would not fit is not started at all, so a full buffer never leaves a
       half-written sensor behind.
    */
    const uint8_t largestRecord =
        PROTOCOL_SENSOR_HEADER_SIZE + PROTOCOL_MAX_FIELDS * PROTOCOL_FIELD_SIZE;
    if ((size_t)length + largestRecord > sizeof(buffer)) {
      return false;
    }

    uint8_t *record = &buffer[length];
    for (uint8_t i = 0; i < PROTOCOL_ID_SIZE; i++) {
      record[i] = reading.id[i] != '\0' ? reading.id[i] : ' ';
    }
    uint8_t *fieldCount = &record[PROTOCOL_ID_SIZE];
    *fieldCount = 0;

    uint8_t *cursor = record + PROTOCOL_SENSOR_HEADER_SIZE;
    addTemperature(cursor, *fieldCount, reading.temperature);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_HUMIDITY, reading.humidity, 100.0f, 655.0f);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_CO2, reading.co2, 1.0f, 65535.0f);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_PM25, reading.pm25, 10.0f, 6553.0f);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_BATTERY, reading.batteryVoltage, 1000.0f, 65.535f);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_PRESSURE, reading.pressure, 10.0f, 6553.5f);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_VOC, reading.voc, 1.0f, 65535.0f);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_NOX, reading.nox, 1.0f, 65535.0f);
    addScaled(cursor, *fieldCount, PROTOCOL_FIELD_LUMINOSITY, reading.luminosity, 1.0f, 65535.0f);

    length = (uint8_t)(cursor - buffer);
    buffer[5]++;
    return true;
  }

  const uint8_t *data() const {
    return buffer;
  }

  uint8_t size() const {
    return length;
  }

  uint8_t sensorCount() const {
    return buffer[5];
  }

private:
  uint8_t buffer[PROTOCOL_HEADER_SIZE
                 + PROTOCOL_MAX_SENSORS
                       * (PROTOCOL_SENSOR_HEADER_SIZE + PROTOCOL_MAX_FIELDS * PROTOCOL_FIELD_SIZE)];
  uint8_t length = 0;

  static void writeUint16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t)(value >> 8);
    p[1] = (uint8_t)(value & 0xFF);
  }

  static void writeField(uint8_t *&cursor, uint8_t &fieldCount, uint8_t type, uint16_t value) {
    cursor[0] = type;
    writeUint16(&cursor[1], value);
    cursor += PROTOCOL_FIELD_SIZE;
    fieldCount++;
  }

  /*
     Temperature is the one signed field, so it gets its own bounds rather than
     sharing the unsigned helper below.
  */
  static void addTemperature(uint8_t *&cursor, uint8_t &fieldCount, float celsius) {
    if (isnan(celsius) || celsius < -327.0f || celsius > 327.0f) {
      return;
    }
    writeField(cursor, fieldCount, PROTOCOL_FIELD_TEMPERATURE,
               (uint16_t)(int16_t)lroundf(celsius * 100.0f));
  }

  /*
     An unsigned field, scaled and range checked. Out of range is left out
     rather than clamped: a value that does not fit is not a measurement, and
     sending a wrong one would be indistinguishable from a real reading.
  */
  static void addScaled(uint8_t *&cursor, uint8_t &fieldCount, uint8_t type,
                        float value, float scale, float limit) {
    if (isnan(value) || value < 0.0f || value > limit) {
      return;
    }
    writeField(cursor, fieldCount, type, (uint16_t)lroundf(value * scale));
  }
};
