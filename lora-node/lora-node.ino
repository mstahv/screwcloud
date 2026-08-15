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

/*
   The radio, with one of the library's protected methods brought back into the
   open. getErrors() asks the chip which block refused to calibrate, which is the
   difference between a diagnosis and six candidates — and a derived class may
   publish what its base declared protected, which is cheaper than a build flag
   the IDE keeps forgetting to apply.
*/
class Core1121 : public LR1121 {
public:
  explicit Core1121(Module *module) : LR1121(module) {}
  using LR11x0::getErrors;
};

// NSS, IRQ, NRST, BUSY — and SPI1, because GP10..GP13 belong to it.
Core1121 radio(new Module(13, 15, 5, 14, SPI1));

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

/*
   How long the oscillator is given to settle before the chip is calibrated
   against it. Waveshare's driver allows 300 ticks of 30.52 us, which is about
   9.2 ms; RadioLib's begin() uses its own default of 5 ms and then calibrates
   immediately. A calibration against an oscillator that has not settled fails,
   and the chip reports it as a failed command — which is error -707 and looks
   like a wiring fault.
*/
static const uint32_t TCXO_STARTUP_US = 10000;

static const unsigned long SEND_INTERVAL_MS = 5000;

static int counter = 0;

/*
   The chip's own error register, which says far more than an error code can.
   These are the blocks it calibrates on the way up, and the two oscillators it
   has to start first — so a failed configuration names the thing that failed
   rather than leaving six candidates.
*/
static void printDeviceErrors() {
  static const struct {
    uint16_t mask;
    const char *name;
  } ERRORS[] = {
    { 1 << 0, "LF_RC_CALIB: the low frequency RC oscillator would not calibrate" },
    { 1 << 1, "HF_RC_CALIB: the high frequency RC oscillator would not calibrate" },
    { 1 << 2, "ADC_CALIB: the converter would not calibrate" },
    { 1 << 3, "PLL_CALIB: the synthesiser would not calibrate" },
    { 1 << 4, "IMG_CALIB: image rejection would not calibrate" },
    { 1 << 5, "HF_XOSC_START: the 32 MHz oscillator did not start — the TCXO" },
    { 1 << 6, "LF_XOSC_START: the 32 kHz oscillator did not start" },
    { 1 << 7, "PLL_LOCK: the synthesiser would not lock" },
  };

  /*
     Standby first: the chip reports no errors while it is doing something else,
     so asking at the wrong moment gives a clean bill of health it does not mean.
  */
  radio.standby();

  uint16_t errors = 0;
  int state = radio.getErrors(&errors);
  if (state != RADIOLIB_ERR_NONE) {
    Serial.printf("  could not read the error register either (%d)\n", state);
    return;
  }
  if (errors == 0) {
    Serial.println("  the chip reports no errors, so the fault is in the exchange"
                   " rather than in the radio");
    return;
  }

  Serial.printf("  device errors 0x%04X:\n", errors);
  for (auto &error : ERRORS) {
    if (errors & error.mask) {
      Serial.printf("    %s\n", error.name);
    }
  }
}

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
  Serial.println("Initialising the radio...");

  /*
     Retried, and the oscillator given a longer start between attempts.

     The first attempt is the one likely to fail: begin() powers the TCXO and
     calibrates against it five milliseconds later, and this module's wants
     nine. After a failure the oscillator is left running, so the next attempt
     calibrates against a clock that has had time to settle.
  */
  int state = RADIOLIB_ERR_UNKNOWN;
  for (int attempt = 1; attempt <= 5 && state != RADIOLIB_ERR_NONE; attempt++) {
    state = radio.begin(config);
    if (state != RADIOLIB_ERR_NONE) {
      Serial.printf("  attempt %d: begin() failed with %d\n", attempt, state);
      printDeviceErrors();
      radio.setTCXO(TCXO_VOLTAGE, TCXO_STARTUP_US);
      delay(50);
    }
  }

  if (state != RADIOLIB_ERR_NONE) {
    /*
       Repeated rather than said once. A diagnostic that speaks only in the
       instant the port was not yet open leaves a board that looks dead, and
       "no output" sends somebody looking for the wrong fault entirely.
    */
    while (true) {
      Serial.printf("begin() failed with %d after five attempts. -2 points at the"
                    " wiring, -707 at the oscillator, -705 at BUSY.\n", state);
      delay(2000);
    }
  }
  Serial.println("Radio ready");

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
