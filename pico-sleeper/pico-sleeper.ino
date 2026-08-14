/*
  ScrewCloud sleeper: a Pico 2 W, its own die temperature, and nothing else.

  An experiment rather than a product. The question it exists to answer is
  whether the RP2350's on-chip temperature sensor is close enough to the air
  around it to be worth reading, on a board that spends nearly all its time
  asleep and therefore cold. If it is, the cheapest possible sensor is a bare
  Pico 2 W with no DHT22, no RuuviTag and nothing wired to it at all.

  The cycle, every SEND_INTERVAL_MS and once at boot:

    wake -> measure -> radio on -> send -> radio off -> sleep

  The order is the experiment. The die is coldest the moment it wakes, and
  everything after the measurement — connecting, sending — heats it. Measuring
  first is what makes the reading about the room rather than about the work.

  It speaks the same protocol as the other readers, so the server files it as an
  ordinary device with one sensor called CPU.

  Requires in Arduino IDE: Raspberry Pi Pico 2 W board.
  Wiring and other details: see the project README.md
*/

#include "config.h"
#include "Protocol.h"
#include "Sleep.h"
#include "Transport.h"

static WiFiTransport wifiTransport;

/*
   A pointer to the interface rather than the class, so that swapping in a
   LoRaWAN transport is one line here and no lines anywhere else. See Transport.h
   for what such an implementation would have to deal with.
*/
static Transport *transport = &wifiTransport;

/*
   The sequence number lives in RAM and so lasts as long as the sketch does. That
   is the whole run today, because the sleep here keeps the program alive; a
   deeper sleep that restarts the chip would set it back to zero on every wake.
   The server treats the sequence as information rather than as a promise, so
   either behaviour is readable — but it is worth knowing which one you are
   looking at.
*/
static uint16_t sequence = 0;

static uint8_t consecutiveFailures = 0;

/*
   Several samples averaged. The ADC reading moves by a few tenths between
   consecutive reads, and a tenth of a degree is meaningless here anyway — but an
   average makes the number stop twitching, which matters when the whole point is
   to compare it against a thermometer on the wall.

   Deliberately quick: every millisecond spent awake is a millisecond of the chip
   warming itself, which is precisely what this device is trying not to measure.
*/
static float readDieTemperature() {
  const uint8_t SAMPLES = 8;
  float sum = 0.0f;
  for (uint8_t i = 0; i < SAMPLES; i++) {
    sum += analogReadTemp();
    delay(2);
  }
  return sum / SAMPLES;
}

/*
   One reading, packed as the protocol wants it. One sensor, so the packet is
   PROTOCOL_HEADER_SIZE + PROTOCOL_SENSOR_SIZE = 16 bytes — which is the number
   that decides whether this fits in a LoRaWAN payload later.
*/
static uint8_t buildPacket(MeasurementPacket &packet, float celsius) {
  packet.begin(DEVICE_ID, ++sequence);

  SensorReading reading;
  snprintf(reading.id, sizeof(reading.id), "%s", INTERNAL_SENSOR_ID);
  reading.temperature = celsius;
  // No humidity: the server shows a missing value as a dash rather than as 0 %.
  reading.humidity = NAN;
  packet.add(reading);

  return packet.size();
}

/*
   The whole job. Returns how long it took, which is the number this experiment
   is really about: at one wake of a few seconds every fifteen minutes, the duty
   cycle is under one percent and what the board draws while asleep is what
   decides how long a battery lasts.
*/
static unsigned long readAndSend() {
  unsigned long start = millis();

  // First, before the radio has had a chance to warm anything.
  float celsius = readDieTemperature();

  MeasurementPacket packet;
  uint8_t length = buildPacket(packet, celsius);

  bool sent = false;
  if (transport->begin()) {
    sent = transport->send(packet.data(), length);
  }
  transport->end();

  if (sent) {
    consecutiveFailures = 0;
  } else if (consecutiveFailures < 255) {
    consecutiveFailures++;
  }

  unsigned long awake = millis() - start;
  Serial.printf("%2.2f C, %s%s, sequence %u, awake %lu ms\n",
                (double)celsius,
                sent ? "sent" : "FAILED: ",
                sent ? "" : transport->lastFailure(),
                sequence, awake);
  if (!sent && consecutiveFailures > 1) {
    Serial.printf("  %u failures in a row\n", consecutiveFailures);
  }
  return awake;
}

void setup() {
  Serial.begin(115200);
  /*
     Not waited for. A device that only wakes for a few seconds must not spend
     them waiting for a serial monitor that is not attached, and the first
     reading is the most interesting one — the chip has been off, so it is as
     close to the room as it will ever be.
  */
  Serial.println();
  Serial.printf("ScrewCloud sleeper, device %s, transport %s\n",
                DEVICE_ID, transport->name());
  Serial.printf("Reading every %lu s\n", SEND_INTERVAL_MS / 1000);

  readAndSend();
}

void loop() {
  Sleep::until(SEND_INTERVAL_MS);
  readAndSend();
}
