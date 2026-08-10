#pragma once

#include <Arduino.h>
#include <math.h>

/*
  Binary wire format of a measurement packet.

  Fixed size and big endian, in the same spirit as Ruuvi's own format. The goal
  is a small packet, because NB-IoT traffic is metered.

  Header, 8 bytes:
    0     version     uint8, currently 1
    1..4  deviceId    4 x ASCII
    5     count       uint8, number of sensors
    6..7  sequence    uint16, increments per packet and wraps

  Sensor, 8 bytes, repeated count times:
    0..3  id          4 x ASCII, space padded
    4..5  temperature int16, unit 0.01 °C, 0x8000 = no value
    6..7  humidity    uint16, unit 0.01 %RH, 0xFFFF = no value

  With three sensors the packet is 32 bytes. The IP and UDP headers already take
  28 bytes, so shrinking the payload further barely affects the total.

  The format is extended by bumping the version, never by quietly adding fields
  — the receiver recognises older devices from the version byte.
*/

static const uint8_t PROTOCOL_VERSION = 1;
static const uint8_t PROTOCOL_HEADER_SIZE = 8;
static const uint8_t PROTOCOL_SENSOR_SIZE = 8;
static const uint8_t PROTOCOL_ID_SIZE = 4;
static const uint8_t PROTOCOL_MAX_SENSORS = 8;

static const int16_t PROTOCOL_TEMPERATURE_INVALID = (int16_t)0x8000;
static const uint16_t PROTOCOL_HUMIDITY_INVALID = 0xFFFF;

/*
   A sensor-agnostic reading. Sensor classes fill this in, which keeps the
   packing logic from knowing anything about the DHT22 or a RuuviTag.

   id is at most 3 characters, because the same identifier is drawn on the OLED
   in the large font where nothing longer fits.
*/
struct SensorReading {
  char id[PROTOCOL_ID_SIZE + 1] = "";
  float temperature = NAN;  // °C
  float humidity = NAN;     // %RH
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
    uint8_t *field = &buffer[length];

    for (uint8_t i = 0; i < PROTOCOL_ID_SIZE; i++) {
      field[i] = reading.id[i] != '\0' ? reading.id[i] : ' ';
    }
    writeInt16(&field[4], encodeTemperature(reading.temperature));
    writeUint16(&field[6], encodeHumidity(reading.humidity));

    length += PROTOCOL_SENSOR_SIZE;
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
  uint8_t buffer[PROTOCOL_HEADER_SIZE + PROTOCOL_MAX_SENSORS * PROTOCOL_SENSOR_SIZE];
  uint8_t length = 0;

  static void writeUint16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t)(value >> 8);
    p[1] = (uint8_t)(value & 0xFF);
  }

  static void writeInt16(uint8_t *p, int16_t value) {
    writeUint16(p, (uint16_t)value);
  }

  /*
     Values that do not fit the field are marked as missing. Silent overflow
     would be worse, because the server could not tell it from a real reading.
  */
  static int16_t encodeTemperature(float celsius) {
    if (isnan(celsius) || celsius < -327.0f || celsius > 327.0f) {
      return PROTOCOL_TEMPERATURE_INVALID;
    }
    return (int16_t)lroundf(celsius * 100.0f);
  }

  static uint16_t encodeHumidity(float percent) {
    if (isnan(percent) || percent < 0.0f || percent > 655.0f) {
      return PROTOCOL_HUMIDITY_INVALID;
    }
    return (uint16_t)lroundf(percent * 100.0f);
  }
};
