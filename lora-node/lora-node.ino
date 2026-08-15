/*
  A Pico with a Core1121, transmitting so that the other end can be tested.

  This is the far side of the link the Raspberry Pi listens to with
  lr1121-java's Smoke tool. It exists to answer one question at a time:

    1. Does anything at all come out of this radio?
    2. Do the two ends agree well enough to decode it?
    3. Does the payload survive intact?

  The parameters below deliberately match `Smoke listen waveshare`, which are in
  turn Waveshare's own example's parameters. There is no negotiation in LoRa and
  no error when two ends disagree — a mismatched spreading factor and a missing
  antenna look exactly alike, which is silence — so the way to bring a link up is
  to make both ends identical first and change one thing at a time afterwards.

  Library: RadioLib 7.7.1 or later, from the Arduino Library Manager.
  Board:   Raspberry Pi Pico or Pico 2 (either works; nothing here is RP2350
           specific).

  Wiring, Core1121 to Pico:

      3.3V -> 3V3(OUT) pin 36        BUSY  -> GP14  pin 19
      GND  -> GND      pin 38        IRQ   -> GP15  pin 20   (DIO9, not DIO1)
      CLK  -> GP10     pin 14        RESET -> GP5   pin 7
      MOSI -> GP11     pin 15
      MISO -> GP12     pin 16        CS    -> GP13  pin 17

  Those are SPI1's pins, hence SPI1 below rather than the default SPI.

  The 868 MHz antenna goes on the sub-GHz connector, and it goes on before
  power: a transmitter driving an open port is a transmitter damaging itself.
*/

#include <RadioLib.h>

// NSS, IRQ, NRST, BUSY — and SPI1, because GP10..GP13 belong to it.
LR1121 radio = new Module(13, 15, 5, 14, SPI1);

/*
   The antenna switch, which is a property of this board and of nothing else.
   Taken from Waveshare's own demo: RFSW0 is DIO5 and carries receive, RFSW1 is
   DIO6 and carries transmit. Other boards wire it differently, and a wrong table
   here gives a radio that reports every success and radiates nothing.
*/
static const uint32_t rfswitch_dio_pins[] = {
  RADIOLIB_LR11X0_DIO5, RADIOLIB_LR11X0_DIO6,
  RADIOLIB_NC, RADIOLIB_NC, RADIOLIB_NC
};

static const Module::RfSwitchMode_t rfswitch_table[] = {
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
   default in the library is 1.6 V, which is right for a different board.
*/
static const float TCXO_VOLTAGE = 3.0;

static const unsigned long SEND_INTERVAL_MS = 5000;

static int counter = 0;

void setup() {
  Serial.begin(115200);

  /*
     Waiting for the host to open the port, not just for time to pass. A Pico
     re-enumerates its USB after an upload, so anything printed in the first
     moment goes nowhere — and a message that goes nowhere is the same as no
     message at all, which is a terrible thing for a diagnostic to be.

     The deadline is there so that a board with nothing attached still runs.
  */
  unsigned long waitingSince = millis();
  while (!Serial && millis() - waitingSince < 5000) {
    delay(10);
  }

  Serial.println();
  Serial.println("Core1121 transmitter");

  SPI1.setSCK(10);
  SPI1.setTX(11);
  SPI1.setRX(12);

  // Before begin(), which is when the oscillator is configured.
  radio.tcxoVoltage = TCXO_VOLTAGE;

  ConfigLoRa_t config;
  config.frequency = 868.0;          // Smoke's "waveshare" preset
  config.bandwidth = 125.0;
  config.spreadingFactor = 7;
  config.codingRate = 5;             // 4/5
  config.syncWord = 0x12;            // private network
  config.preambleLength = 8;
  config.power = 14;                 // dBm, the low power path

  // Printed before the call, so that a hang inside it is visible as one.
  Serial.print("Initialising the radio... ");
  int state = radio.begin(config);
  if (state != RADIOLIB_ERR_NONE) {
    Serial.println();
    /*
       Repeated rather than said once. A diagnostic that speaks only in the
       instant the port was not yet open leaves a board that looks dead, and
       "no output" sends somebody looking for the wrong fault entirely.
    */
    while (true) {
      Serial.printf("begin() failed with %d. Check the wiring, the TCXO voltage"
                    " and that the module has power.\n", state);
      delay(2000);
    }
  }
  Serial.println("ok");

  radio.setRfSwitchTable(rfswitch_dio_pins, rfswitch_table);

  /*
     Off, because Waveshare's example has it off and the receiver is matching
     that example. A receiver expecting a CRC hears a packet without one as a
     packet that failed its CRC, and drops it — with no way to tell that from
     no packet at all.
  */
  radio.setCRC(0);

  Serial.println("868.000 MHz, SF7, BW 125 kHz, CR 4/5, sync 0x12, preamble 8,"
                 " CRC off, 14 dBm");
  Serial.printf("Sending every %lu ms\n", SEND_INTERVAL_MS);
}

void loop() {
  /*
     The counter is in the payload so that the receiver can tell packets
     arriving from one packet arriving and being printed again — which is the
     first thing to doubt when the same line keeps appearing.
  */
  char payload[24];
  int length = snprintf(payload, sizeof(payload), "pico %d", counter++);

  int state = radio.transmit((uint8_t *)payload, length);

  if (state == RADIOLIB_ERR_NONE) {
    Serial.printf("Sent \"%s\", %d bytes, %.1f ms in the air\n",
                  payload, length, radio.getTimeOnAir(length) / 1000.0);
  } else {
    Serial.printf("Transmit failed with %d\n", state);
  }

  delay(SEND_INTERVAL_MS);
}
