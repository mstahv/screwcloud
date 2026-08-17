#pragma once

#include <Arduino.h>
#include "Transport.h"

/*
  The other transport the Transport interface was written for.

  A wake here costs milliseconds rather than the seconds WiFi spends connecting,
  and there is no network to be in range of — which is the entire point of this
  device carrying a second radio at all. What it gives up is any acknowledgement
  that anything heard it: a LoRa packet sent this way is a shout into a field.
  The Raspberry Pi running pi-reader is what listens, and it relays the bytes to
  the server untouched, so a node out here appears there as itself.

  **Both ends must agree exactly.** There is no negotiation in LoRa and no error
  when two ends disagree; a mismatched spreading factor, a mismatched CRC setting
  and a missing antenna all sound alike, which is silence. The settings below are
  the ones the link has actually been made to work with — lora-node.ino's, which
  are Waveshare's own example's — and their counterparts on the Pi are
  screwcloud.lora.* in pi-reader's application.properties. Change one end and the
  other in the same breath, and one thing at a time.

  Library: RadioLib 7.7.1 or later, from the Arduino Library Manager.

  Wiring, Core1121 to Pico, the same as lora-node.ino:

      3.3V -> 3V3(OUT) pin 36        BUSY  -> GP14  pin 19
      GND  -> GND      pin 38        IRQ   -> GP15  pin 20   (DIO9, not DIO1)
      CLK  -> GP10     pin 14        RESET -> GP5   pin 7
      MOSI -> GP11     pin 15
      MISO -> GP12     pin 16        CS    -> GP13  pin 17

  Those are SPI1's pins, hence SPI1 below rather than the default SPI.

  The 868 MHz antenna goes on the sub-GHz connector, and it goes on before power:
  a transmitter driving an open port is a transmitter damaging itself.
*/

/*
   Defaulted here rather than required in config.h, because config.h is
   gitignored: anyone who already has one would find the sketch stopped compiling
   after a pull otherwise. config.h.example documents them, and a value set there
   wins.
*/
#ifndef LORA_FREQUENCY_MHZ
#define LORA_FREQUENCY_MHZ 868.0
#endif

#ifndef LORA_SPREADING_FACTOR
#define LORA_SPREADING_FACTOR 7
#endif

/*
   Off, because that is what the working link does. Worth turning on at both ends
   once a link is up — without it a corrupt packet is relayed to the server as
   though it were good — but it is a change to make deliberately, with
   screwcloud.lora.crc on the Pi moved in the same breath.
*/
#ifndef LORA_CRC
#define LORA_CRC 0
#endif

/*
   14 dBm is the EU868 limit on most channels. The duty cycle limit in that band
   is 1 %, which this device comes nowhere near: 16 bytes at SF7 is about 60 ms in
   the air, once every fifteen minutes. Even SF12, at roughly 1.3 s, is under a
   fifth of a percent.
*/
#ifndef LORA_TX_POWER_DBM
#define LORA_TX_POWER_DBM 14
#endif

#include <RadioLib.h>

/*
   The radio, with two of the library's protected methods brought back into the
   open — the same subclass lora-node.ino uses, and for the same reasons.
   setOutputPower in its six argument form is the one that matters here; see
   where it is called.
*/
class Core1121Radio : public LR1121 {
public:
  explicit Core1121Radio(Module *module) : LR1121(module) {}
  using LR11x0::getErrors;
  using LR11x0::getVersionInfo;
  using LR11x0::setOutputPower;
};

/* The wiring at the top of this file, named, because the probe needs it too. */
static const int LORA_PIN_CS = 13;
static const int LORA_PIN_IRQ = 15;
static const int LORA_PIN_RESET = 5;
static const int LORA_PIN_BUSY = 14;
static const int LORA_PIN_SCK = 10;
static const int LORA_PIN_MOSI = 11;
static const int LORA_PIN_MISO = 12;

// NSS, IRQ, NRST, BUSY — and SPI1, because GP10..GP13 belong to it.
static Core1121Radio loraRadio(new Module(LORA_PIN_CS, LORA_PIN_IRQ,
                                          LORA_PIN_RESET, LORA_PIN_BUSY, SPI1));

/*
   The antenna switch, which is a property of this board and of nothing else:
   RFSW0 is DIO5 and carries receive, RFSW1 is DIO6 and carries transmit. A wrong
   table here gives a radio that reports every success and radiates nothing —
   which is why lora-node.ino tested this one against its opposite before it was
   believed. The opposite produced nothing at all.
*/
static const uint32_t LORA_RFSWITCH_PINS[] = {
  RADIOLIB_LR11X0_DIO5, RADIOLIB_LR11X0_DIO6,
  RADIOLIB_NC, RADIOLIB_NC, RADIOLIB_NC
};

static const Module::RfSwitchMode_t LORA_RFSWITCH_TABLE[] = {
  // mode                  DIO5  DIO6
  { LR11x0::MODE_STBY,   { LOW,  LOW  } },
  { LR11x0::MODE_RX,     { HIGH, LOW  } },
  { LR11x0::MODE_TX,     { LOW,  HIGH } },
  { LR11x0::MODE_TX_HP,  { LOW,  HIGH } },
  { LR11x0::MODE_TX_HF,  { LOW,  LOW  } },
  { LR11x0::MODE_GNSS,   { LOW,  LOW  } },
  { LR11x0::MODE_WIFI,   { LOW,  LOW  } },
  END_OF_MODE_TABLE,
};

/*
   The module has a 3.0 V temperature compensated oscillator. This is the single
   most common reason an LR11xx answers its commands and then hears nothing: the
   library's default is 1.6 V, which is right for a different board.
*/
static const float LORA_TCXO_VOLTAGE = 3.0;

/* What the vendor's driver allows the oscillator to settle, in microseconds. */
static const uint32_t LORA_TCXO_STARTUP_US = 10000;

class LoraTransport : public Transport {
public:

  /*
     Whether there is an LR1121 on the SPI bus at all — one command, by hand, with
     no library between us and the wires.

     This is what makes "use LoRa if the hardware is there" answerable. It cannot
     be delegated to RadioLib: findChip() returns a bool, and the macro it uses to
     check for errors does "return(state)", so a failed version read comes back as
     (bool)(-707), which is true. Every conclusion drawn after that point comes
     from a detection that never happened.

     GetVersion is the right command to do by hand: two bytes out, five back, no
     configuration needed, and its answer is known — this module reports hardware
     0x22, device 0x03.

     **Retried, and that is the whole difficulty.** lora-node.ino runs the same
     probe, but only after waiting up to five seconds for a serial monitor, so its
     radio has had seconds of power before anything speaks to it. This sketch
     deliberately waits for nothing — a device that wakes for a few seconds must
     not spend them on a monitor that is not attached — so a single read here lands
     milliseconds after power-up, gets all zeroes from a chip that is still
     starting, and concludes there is no radio. Which is exactly the wrong answer,
     arrived at confidently.

     lora-node.ino has the same hazard and answers it the same way, for its
     begin(): "the radio simply needs longer than a Pico takes to reach setup(); on
     a Raspberry Pi the module has been powered for the whole of Linux booting."
     There the probe could afford to be a diagnostic that gates nothing. Here it
     decides which radio the device uses, so it has to be right.

     A board with no radio pays the full wait, about a second and a half, once at
     boot — and then goes on to spend several seconds joining a WiFi network.
  */
  static bool radioAnswers() {
    for (int attempt = 1; attempt <= PROBE_ATTEMPTS; attempt++) {
      uint8_t answer[5];
      probeOnce(answer);

      bool silent = (answer[1] == 0x00 && answer[2] == 0x00);
      bool floating = (answer[1] == 0xFF && answer[2] == 0xFF);

      if (!silent && !floating) {
        Serial.printf("LoRa probe: %02X %02X %02X %02X %02X — a radio answered"
                      " (hardware 0x%02X, device 0x%02X)\n",
                      answer[0], answer[1], answer[2], answer[3], answer[4],
                      answer[1], answer[2]);
        return true;
      }

      if (attempt == PROBE_ATTEMPTS) {
        Serial.printf("LoRa probe: %02X %02X %02X %02X %02X — %s after %d attempts\n",
                      answer[0], answer[1], answer[2], answer[3], answer[4],
                      silent ? "nothing there" : "MISO floating, nothing there",
                      PROBE_ATTEMPTS);
        return false;
      }

      /*
         Growing, so that a chip which is merely slow to start is given more time
         on each pass rather than being asked again immediately at the same
         unhelpful moment.
      */
      delay(attempt * 150UL);
    }
    return false;
  }

  /*
     Configured once and then slept between wakes, rather than initialised on
     each one.

     begin() on this chip powers the oscillator, calibrates against it and
     re-applies every setting, and the first attempt after a cold power-up is the
     one likely to fail — the calibration happens five milliseconds in and this
     module wants nine. lora-node.ino answers that with a growing retry, which is
     the right answer once at boot and a poor one every fifteen minutes.

     So the cost is paid in setup() and the radio is put to sleep holding its
     configuration afterwards. Waking it is a standby command: no calibration, no
     settling, no retry. If a wake or a transmission ever fails, the flag is
     cleared and the next wake pays for a full initialisation again, so a radio
     that has lost its configuration recovers by itself rather than going quiet
     until somebody power cycles it.
  */
  bool begin() override {
    if (!configured) {
      configured = initialise();
      return configured;
    }

    int state = loraRadio.standby();
    if (state != RADIOLIB_ERR_NONE) {
      Serial.printf("LoRa: waking failed (%d), configuring from scratch\n", state);
      configured = initialise();
      return configured;
    }
    failure = "";
    return true;
  }

  bool send(const uint8_t *data, uint8_t length) override {
    /*
       Cast because RadioLib takes a non-const pointer in some versions and a
       const one in others, and this compiles against both. It does not write to
       the buffer.
    */
    int state = loraRadio.transmit((uint8_t *)data, length);

    if (state != RADIOLIB_ERR_NONE) {
      /*
          Force a full initialisation next time. A chip that refuses to transmit
          has usually lost the configuration it was sleeping on, and re-applying
          it costs one wake where being wrong costs every wake after this one.
      */
      configured = false;
      failure = describe(state);
      return false;
    }

    Serial.printf("LoRa: sent %u bytes, %.1f ms in the air, %d dBm\n",
                  length, loraRadio.getTimeOnAir(length) / 1000.0,
                  (int)LORA_TX_POWER_DBM);
    failure = "";
    return true;
  }

  /*
     Asleep, holding its configuration. This is the state the board spends
     essentially all its time in, so it is the one that decides what a battery
     lasts — and unlike WiFi.end(), it costs nothing to come back from.
  */
  void end() override {
    loraRadio.sleep();
  }

  const char *name() const override {
    return "LoRa";
  }

  const char *lastFailure() const override {
    return failure;
  }

private:
  /*
     Five, with a growing pause between them: 150, 300, 450 and 600 ms, so a chip
     that needs a moment has about a second and a half to turn up.
  */
  static const int PROBE_ATTEMPTS = 5;

  bool configured = false;
  const char *failure = "";

  /*
     One reset and one GetVersion, leaving the five bytes the chip sent back.

     The reset is repeated on every attempt on purpose: a chip caught mid
     power-up is better restarted than asked again, and this doubles as the
     known state that the initialise() which may follow wants anyway.
  */
  static void probeOnce(uint8_t answer[5]) {
    pinMode(LORA_PIN_CS, OUTPUT);
    digitalWrite(LORA_PIN_CS, HIGH);
    pinMode(LORA_PIN_BUSY, INPUT);
    pinMode(LORA_PIN_RESET, OUTPUT);

    SPI1.setSCK(LORA_PIN_SCK);
    SPI1.setTX(LORA_PIN_MOSI);
    SPI1.setRX(LORA_PIN_MISO);
    SPI1.begin();

    // Reset, and give it the settling time the vendor's driver allows.
    digitalWrite(LORA_PIN_RESET, LOW);
    delay(10);
    digitalWrite(LORA_PIN_RESET, HIGH);
    delay(300);

    SPI1.beginTransaction(SPISettings(2000000, MSBFIRST, SPI_MODE0));

    // GetVersion: opcode 0x0101, no arguments.
    digitalWrite(LORA_PIN_CS, LOW);
    SPI1.transfer(0x01);
    SPI1.transfer(0x01);
    digitalWrite(LORA_PIN_CS, HIGH);

    // The chip answers in a transaction of its own, once it is ready.
    unsigned long deadline = millis() + 100;
    while (digitalRead(LORA_PIN_BUSY) && millis() < deadline) {
      delayMicroseconds(100);
    }

    digitalWrite(LORA_PIN_CS, LOW);
    for (uint8_t i = 0; i < 5; i++) {
      answer[i] = SPI1.transfer(0x00);
    }
    digitalWrite(LORA_PIN_CS, HIGH);
    SPI1.endTransaction();
  }

  bool initialise() {
    SPI1.setSCK(LORA_PIN_SCK);
    SPI1.setTX(LORA_PIN_MOSI);
    SPI1.setRX(LORA_PIN_MISO);

    // Before begin(), which is when the oscillator is configured.
    loraRadio.tcxoVoltage = LORA_TCXO_VOLTAGE;

    ConfigLoRa_t config;
    config.frequency = LORA_FREQUENCY_MHZ;
    config.bandwidth = 125.0;
    config.spreadingFactor = LORA_SPREADING_FACTOR;
    config.codingRate = 5;   // 4/5
    config.syncWord = 0x12;  // private network
    config.preambleLength = 8;
    config.power = LORA_TX_POWER_DBM;

    /*
       Retried, with the oscillator given a longer start between attempts, for
       the reason in the comment on begin(): the first attempt after power-up
       calibrates against a clock that has not settled.
    */
    int state = RADIOLIB_ERR_UNKNOWN;
    for (int attempt = 1; attempt <= 5 && state != RADIOLIB_ERR_NONE; attempt++) {
      state = loraRadio.begin(config);
      if (state != RADIOLIB_ERR_NONE) {
        Serial.printf("LoRa: attempt %d failed (%d)\n", attempt, state);
        loraRadio.setTCXO(LORA_TCXO_VOLTAGE, LORA_TCXO_STARTUP_US);
        delay(attempt * 500UL);
      }
    }

    if (state != RADIOLIB_ERR_NONE) {
      failure = describe(state);
      Serial.printf("LoRa: giving up after five attempts (%d). %s\n", state, failure);
      return false;
    }

    loraRadio.setRfSwitchTable(LORA_RFSWITCH_PINS, LORA_RFSWITCH_TABLE);

    /*
       The amplifier the board is actually wired to, rather than the one the
       library picks from the power asked for.

       Waveshare's own table selects the high power amplifier at every level from
       -9 to +22 dBm without variation, which says the low power output goes
       nowhere on this module. Asking for 14 dBm the ordinary way selects the low
       power path, and the radio then transmits into an unconnected pin —
       happily, reporting success, while the far end hears about ninety decibels
       less than it should.

       select HP, supplied from the battery rail, duty cycle 0x04, size 0x07,
       48 us ramp.
    */
    int power = loraRadio.setOutputPower(LORA_TX_POWER_DBM, 0x01, 0x01, 0x04, 0x07, 0x02);
    if (power != RADIOLIB_ERR_NONE) {
      Serial.printf("LoRa: setOutputPower failed (%d) — expect a very quiet radio\n", power);
    }

    loraRadio.setCRC(LORA_CRC ? 2 : 0);

    Serial.printf("LoRa ready: %.3f MHz, SF%d, BW 125 kHz, CR 4/5, sync 0x12,"
                  " preamble 8, CRC %s, %d dBm\n",
                  (double)LORA_FREQUENCY_MHZ, (int)LORA_SPREADING_FACTOR,
                  LORA_CRC ? "on" : "off", (int)LORA_TX_POWER_DBM);
    failure = "";
    return true;
  }

  /* The three codes worth recognising by sight, from bringing this link up. */
  static const char *describe(int state) {
    switch (state) {
      case -2:
        return "no chip: suspect the wiring";
      case -705:
        return "BUSY never fell";
      case -707:
        return "a command was refused: suspect the oscillator";
      default:
        return "radio error";
    }
  }
};
