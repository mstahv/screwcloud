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

/*
   Which radio the readings leave by.

   TRANSPORT in config.h takes one of three values:

     USE_AUTO   LoRa if a Core1121 answers on the SPI bus, WiFi if it does not
     USE_LORA   LoRa, with nothing to fall back on
     USE_WIFI   WiFi, and RadioLib need not be installed at all

   USE_AUTO is the default, and it is what a board that is sometimes wired to a
   radio and sometimes not actually wants: the question is settled by the hardware
   at boot rather than by a flag somebody forgot to change before flashing. It
   costs one SPI command and about a third of a second, once.

   The catch is that USE_AUTO compiles both, so RadioLib has to be installed even
   on a board that will end up choosing WiFi. USE_WIFI is there for the board that
   never has a radio and should not need the library.

   The two are not interchangeable in what they promise. WiFi reaches a server
   directly and fails if there is no network; LoRa reaches a Raspberry Pi running
   pi-reader, which relays the bytes onward, and cannot tell whether anything heard
   it. What they have in common is that neither waits for an acknowledgement, which
   is what lets the same sketch use either.

   Defaulted here rather than required in config.h, because config.h is gitignored
   and a constant required there would stop the sketch compiling for everyone who
   already has one.
*/
#define USE_WIFI 0
#define USE_LORA 1
#define USE_AUTO 2

#ifndef TRANSPORT
#define TRANSPORT USE_AUTO
#endif

#if TRANSPORT != USE_WIFI
#include "LoraTransport.h"
static LoraTransport loraTransport;
#endif

#if TRANSPORT != USE_LORA
static WiFiTransport wifiTransport;
#endif

/*
   A pointer to the interface rather than the class, so that swapping the
   transport is these few lines and none anywhere else.

   Filled in by setup() rather than here: with USE_AUTO the answer comes off the
   SPI bus, and there is no SPI bus yet when statics are initialised.
*/
static Transport *transport = nullptr;

static Transport *chooseTransport() {
#if TRANSPORT == USE_WIFI
  return &wifiTransport;
#elif TRANSPORT == USE_LORA
  return &loraTransport;
#else
  if (LoraTransport::radioAnswers()) {
    return &loraTransport;
  }
  Serial.println("No LoRa radio on the SPI bus — using WiFi.");
  return &wifiTransport;
#endif
}

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
  const uint8_t DISCARDED = 2;
  const uint8_t SAMPLES = 8;

  /*
     The first conversions after the ADC has been idle are the least trustworthy,
     and this reading is taken at the one moment in the cycle when the ADC has
     been idle for fifteen minutes. Two thrown away costs four milliseconds.
  */
  for (uint8_t i = 0; i < DISCARDED; i++) {
    analogReadTemp();
    delay(2);
  }

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

  /*
     The same sensor again, now that the work is done and the radio has been on.
     Not sent anywhere — it is the instrument for the question this device exists
     to ask. The difference between the two is how much the board heated itself
     during the wake, and if that number is large then the reading which is sent
     is only as good as the cooling that precedes it.
  */
  float afterwards = readDieTemperature();

  unsigned long awake = millis() - start;
  Serial.printf("%2.2f C, %s%s, sequence %u, awake %lu ms, self-heating %+.2f C\n",
                (double)celsius,
                sent ? "sent" : "FAILED: ",
                sent ? "" : transport->lastFailure(),
                sequence, awake, (double)(afterwards - celsius));
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

  /*
     Before anything is printed about it and before the first reading, since with
     USE_AUTO nothing yet knows which radio this board has.
  */
  transport = chooseTransport();

  /*
     The compile time, so that "did the upload actually take?" is a question with
     an answer. A board that is still running yesterday's firmware looks exactly
     like a board whose new code does not work, and the two send you looking in
     completely different places.
  */
  Serial.printf("ScrewCloud sleeper, device %s, transport %s, built %s %s\n",
                DEVICE_ID, transport->name(), __DATE__, __TIME__);
  Serial.printf("Reading every %lu s, sleeping with %s\n",
                SEND_INTERVAL_MS / 1000, Sleep::description());

  /*
     Marked, because it is the one reading of the run that cannot be compared with
     the others: uploading the firmware and a USB host on the other end of the
     cable both warm the board, and neither happens before any later reading.
  */
  Serial.println("First reading follows the upload, so treat it with suspicion:");
  readAndSend();
}

void loop() {
  Sleep::until(SEND_INTERVAL_MS);
  readAndSend();
}
