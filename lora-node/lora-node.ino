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
  using LR11x0::getVersionInfo;
  using LR11x0::setOutputPower;
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

/*
   What Waveshare's driver configures, read across into RadioLib's form: receive
   on RFSW0, transmit on RFSW1, with RFSW0 as DIO5 and RFSW1 as DIO6.

   That mapping was a translation and therefore a guess, so it was tested by
   sending alternately with it and with its opposite. The opposite produced
   nothing at all, which settles it.
*/
static const Module::RfSwitchMode_t rfswitch_vendor[] = {
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

static const int TX_POWER_DBM = 14;

static const unsigned long SEND_INTERVAL_MS = 5000;

/*
   Set to true to listen instead of transmitting, and run "Smoke send waveshare"
   on the Raspberry Pi.

   This reverses the link, which is how to test the one thing left untested. The
   Pi's antenna is proved — pulling it stopped reception — and its transmit path
   uses the vendor's switch configuration numerically, with nothing assumed. So
   if this end hears the Pi strongly, its antenna is fine and the fault is in how
   this end transmits; if it hears the Pi at the same hundred and ten decibels
   down, the fault is this end's antenna.

   Either answer arrives without transmitting into a port that may have nothing
   on it, which is the one experiment worth avoiding.
*/
static const bool LISTEN_INSTEAD_OF_SENDING = false;

static int counter = 0;

static const int PIN_CS = 13;
static const int PIN_RESET = 5;
static const int PIN_BUSY = 14;

/*
   One command, by hand, with no library between us and the wires.

   This exists because RadioLib's chip detection cannot be trusted to have run:
   findChip() returns a bool, and the macro it uses to check for errors does
   "return(state)" — so a failed version read comes back as (bool)(-707), which
   is true. Every conclusion after that point is drawn from a detection that
   never happened.

   GetVersion is the right command to do by hand: two bytes out, five back, no
   configuration needed, and its answer is known — the module on the Raspberry Pi
   reports hardware 0x22, device 0x03, firmware 1.1.

   What the bytes say:
     all 0x00    MISO is not arriving, or the chip is not driving it
     all 0xFF    MISO is floating high — the same fault, other polarity
     sensible    the wiring is fine and the fault is above it
*/
static void probeByHand() {
  Serial.println("Reading the version with no library in the way:");

  pinMode(PIN_CS, OUTPUT);
  digitalWrite(PIN_CS, HIGH);
  pinMode(PIN_BUSY, INPUT);
  pinMode(PIN_RESET, OUTPUT);

  SPI1.setSCK(10);
  SPI1.setTX(11);
  SPI1.setRX(12);
  SPI1.begin();

  // Reset, and give it the settling time the vendor's driver allows.
  digitalWrite(PIN_RESET, LOW);
  delay(10);
  digitalWrite(PIN_RESET, HIGH);
  delay(300);

  Serial.printf("  BUSY reads %d after reset (0 means ready)\n", digitalRead(PIN_BUSY));

  SPI1.beginTransaction(SPISettings(2000000, MSBFIRST, SPI_MODE0));

  // GetVersion: opcode 0x0101, no arguments.
  digitalWrite(PIN_CS, LOW);
  SPI1.transfer(0x01);
  SPI1.transfer(0x01);
  digitalWrite(PIN_CS, HIGH);

  // The chip answers in a transaction of its own, once it is ready.
  unsigned long deadline = millis() + 100;
  while (digitalRead(PIN_BUSY) && millis() < deadline) {
    delayMicroseconds(100);
  }
  if (digitalRead(PIN_BUSY)) {
    Serial.println("  BUSY never fell — the chip is not answering at all");
  }

  uint8_t answer[5];
  digitalWrite(PIN_CS, LOW);
  for (uint8_t i = 0; i < sizeof(answer); i++) {
    answer[i] = SPI1.transfer(0x00);
  }
  digitalWrite(PIN_CS, HIGH);
  SPI1.endTransaction();

  Serial.printf("  raw: %02X %02X %02X %02X %02X\n",
                answer[0], answer[1], answer[2], answer[3], answer[4]);
  Serial.printf("  status 0x%02X, hardware 0x%02X, device 0x%02X, firmware %d.%d\n",
                answer[0], answer[1], answer[2], answer[3], answer[4]);

  if (answer[1] == 0x00 && answer[2] == 0x00) {
    Serial.println("  nothing but zeroes: suspect MISO, or that the module has no power");
  } else if (answer[1] == 0xFF && answer[2] == 0xFF) {
    Serial.println("  nothing but ones: MISO is floating — suspect the MISO joint");
  } else {
    Serial.println("  the chip answered, so the wires are good");
  }
  Serial.println();
}

/*
   The chip's own error register, which says far more than an error code can.
   These are the blocks it calibrates on the way up, and the two oscillators it
   has to start first — so a failed configuration names the thing that failed
   rather than leaving six candidates.
*/
/*
   What the chip says it is. This is the line that separates a radio which is
   merely misconfigured from one that has no working firmware at all: in
   bootloader mode it reports itself as 0xDF, answers a version query, and
   refuses every other command — which is exactly the shape of a failure where
   getErrors() fails too.

   RadioLib's findChip() accepts a bootloader as "found", so begin() gets past it
   and then fails on the first real command.
*/
static void printChipIdentity() {
  LR11x0VersionInfo_t info;
  int state = radio.getVersionInfo(&info);
  if (state != RADIOLIB_ERR_NONE) {
    Serial.printf("  getVersionInfo failed too (%d)\n", state);
    return;
  }

  const char *what;
  switch (info.device) {
    case RADIOLIB_LR11X0_DEVICE_LR1121:
      what = "LR1121, normal mode";
      break;
    case RADIOLIB_LR11X0_DEVICE_BOOT:
      what = "BOOTLOADER — the chip has no working firmware, and only firmware"
             " updates will be accepted";
      break;
    default:
      what = "not a device this library knows";
      break;
  }
  Serial.printf("  device 0x%02X: %s\n", info.device, what);
  Serial.printf("  hardware 0x%02X, firmware %d.%d\n",
                info.hardware, info.fwMajor, info.fwMinor);
}

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

/* Waits for one packet and reports it, or says the wait ran out. */
static void listen() {
  uint8_t payload[64];
  /*
     Zero, not the buffer size. That parameter is the expected packet length,
     which a receiver only knows in advance in implicit header mode — here the
     length travels in the packet, and telling the library to expect sixty four
     bytes of it produced a reception of zero.
  */
  int state = radio.receive(payload, 0);

  if (state == RADIOLIB_ERR_NONE) {
    int length = radio.getPacketLength();
    Serial.printf("Received %d bytes, RSSI %.1f dBm, SNR %.1f dB: ",
                  length, radio.getRSSI(), radio.getSNR());
    for (int i = 0; i < length && i < (int) sizeof(payload); i++) {
      Serial.printf("%02X", payload[i]);
    }
    Serial.println();
  } else if (state == RADIOLIB_ERR_RX_TIMEOUT) {
    Serial.println("Nothing heard");
  } else {
    Serial.printf("receive() failed with %d\n", state);
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
  config.power = TX_POWER_DBM;

  probeByHand();

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
      printChipIdentity();
      printDeviceErrors();
      radio.setTCXO(TCXO_VOLTAGE, TCXO_STARTUP_US);

      /*
         Growing, to test the idea that the radio simply needs longer than a
         Pico takes to reach setup(). On a Raspberry Pi the module has been
         powered for the whole of Linux booting before anything talks to it; here
         it is milliseconds. By the fifth attempt it has had seven seconds.
      */
      unsigned long settle = attempt * 500UL;
      Serial.printf("  waiting %lu ms before trying again\n", settle);
      delay(settle);
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

  radio.setRfSwitchTable(rfswitch_dio_pins, rfswitch_vendor);

  /*
     The amplifier the board is actually wired to, rather than the one the
     library picks from the power asked for.

     Waveshare's own table selects the high power amplifier at every level from
     -9 to +22 dBm without variation, which says the low power output goes
     nowhere on this module. Asking for 14 dBm the ordinary way selects the low
     power path, and the radio then transmits into an unconnected pin — happily,
     reporting success, while the far end hears about ninety decibels less than
     it should.

     select HP, supplied from the battery rail, duty cycle 0x04, size 0x07,
     48 us ramp.
  */
  int power = radio.setOutputPower(TX_POWER_DBM, 0x01, 0x01, 0x04, 0x07, 0x02);
  if (power != RADIOLIB_ERR_NONE) {
    Serial.printf("setOutputPower failed with %d\n", power);
  }

  /*
     Off, because Waveshare's example has it off and the receiver is matching
     that example. A receiver expecting a CRC hears a packet without one as a
     packet that failed its CRC, and drops it — with no way to tell that from
     no packet at all.
  */
  radio.setCRC(0);

  Serial.println("868.000 MHz, SF7, BW 125 kHz, CR 4/5, sync 0x12, preamble 8,"
                 " CRC off, 14 dBm");
  if (LISTEN_INSTEAD_OF_SENDING) {
    Serial.println("Listening. Run \"Smoke send waveshare\" on the Raspberry Pi.");
  } else {
    Serial.printf("Sending every %lu ms\n", SEND_INTERVAL_MS);
  }
}

void loop() {
  if (LISTEN_INSTEAD_OF_SENDING) {
    listen();
    return;
  }

  /*
     The payload says which table sent it, so the receiver's log answers the
     question without the two ends having to agree on anything beforehand. The
     counter is in there too, so that packets arriving can be told from one
     packet arriving and being printed again.
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
