#pragma once

#include <Arduino.h>
#include "config.h"

/*
  Sending measurements to the server. Two implementations behind one interface:
  local WiFi, and the SIM7028 NB-IoT modem for places with no network. Which one
  is used is decided in config.h, either fixed or auto-detected.

  The implementations live in the header because an Arduino sketch is a single
  translation unit and a separate .cpp is not worth it here.
*/

/*
   Called from inside wait loops. The sketch wires this to BTstack.loop() so BLE
   advertisements keep arriving while a connection is being established or an AT
   response is awaited. Without it, waiting for NB-IoT registration would stall
   BLE for minutes.
*/
void transportIdle();

class Transport {
public:
  virtual ~Transport() {}

  // Called from setup(). Failure is not final — send() retries as needed.
  virtual bool begin() = 0;

  virtual bool send(const uint8_t *data, uint8_t length) = 0;

  virtual const char *name() const = 0;

  /*
     Why the last send failed, in the few characters the OLED's bottom row has
     left. "FAIL!" alone tells a reader that something is wrong but not whether
     to check the antenna, the SIM, the coverage or the server — and those are
     the four different afternoons that follow.
  */
  virtual const char *lastFailure() const {
    return "";
  }

  /*
     Whether the link is waiting for a person rather than for time to pass. The
     sketch's last-resort reboot asks before restarting, because a restart is
     only ever a way of clearing state that got stuck — and a state that a person
     has to come and fix is not that. Rebooting into it would just burn the same
     attempt again on every cycle, which for a SIM PIN is how a card ends up
     behind its PUK.
  */
  virtual bool needsHumanHelp() const {
    return false;
  }
};

#ifdef USE_WIFI

#include <WiFi.h>
#include <WiFiUdp.h>

class WiFiTransport : public Transport {
public:
  bool begin() override {
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    return waitForConnection();
  }

  bool send(const uint8_t *data, uint8_t length) override {
    if (WiFi.status() != WL_CONNECTED && !begin()) {
      failure = "NO WIFI";
      return false;
    }
    if (udp.beginPacket(SERVER_HOST, SERVER_PORT) != 1) {
      // Almost always the server name not resolving.
      failure = "NO HOST";
      return false;
    }
    udp.write(data, length);
    if (udp.endPacket() != 1) {
      failure = "NO SEND";
      return false;
    }
    failure = "";
    return true;
  }

  const char *name() const override {
    return "WiFi";
  }

  const char *lastFailure() const override {
    return failure;
  }

private:
  WiFiUDP udp;
  const char *failure = "";

  bool waitForConnection() {
    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED) {
      if (millis() - start > WIFI_CONNECT_TIMEOUT_MS) {
        return false;
      }
      transportIdle();
    }
    return true;
  }
};

#endif  // USE_WIFI

#ifdef USE_NBIOT

/*
   UART0 default pins. GP0 = physical pin 1, GP1 = physical pin 2.
   The Pico's TX goes to the HAT's RX and vice versa.
*/
static const uint8_t NBIOT_TX_PIN = 0;
static const uint8_t NBIOT_RX_PIN = 1;

/*
   The recovery ladder for a link that has stopped working, counted in
   consecutive failed sends — one every SEND_INTERVAL_MS.

   Here rather than in config.h because this is not a property of anyone's
   installation: it is what to do when the module stops answering, and the same
   answer suits every device. config.h is also gitignored, so a constant added
   there would fail to compile for everyone who already has one.

   After every failure the module is resynchronised. Beyond that: reopen the
   socket and the PDP context, and then restart the module, which costs a minute
   of re-registration and is why it is not tried first.

   The restarts themselves escalate, counted in how many have been tried since
   the last send got through. The first is AT+CRESET. If the module comes back
   from that and still cannot send, the second goes deeper — AT&F to throw away
   a command profile that may have been corrupted, then AT+CFUN=1,1, which is
   3GPP's own reset and reaches the module through different code than the
   vendor command does. Past that there is nothing left that a command can do,
   and the device says so rather than going on pretending.

   Which is the honest limit of all of this: every one of these needs the module
   to still be parsing AT commands, and the failure they are most needed for is
   the one where it is not. A module that has stopped listening is reached only
   by taking its power away, and this firmware has no wire that can do that.
*/
static const uint8_t NBIOT_REOPEN_AFTER_FAILURES = 2;
static const uint8_t NBIOT_RESET_AFTER_FAILURES = 4;

/*
   How long a reset is given to bring the module back to answering AT. A SIMCom
   module boots in well under this; the wait is here so that the log can say
   whether the reset took rather than leaving it to be guessed from what fails
   next.
*/
static const unsigned long NBIOT_RESET_RECOVERY_MS = 30000;

/*
   How long the reset line is held asserted. A property of the module rather
   than of anyone's wiring, which is why it is here and only the pin number is
   in config.h.

   NOT YET CHECKED against the SIM7028 hardware design document — both copies
   of it refused to be fetched. SIMCom modules generally want something around
   a hundred milliseconds and this is deliberately past that. If the reset turns
   out not to take, the pulse width is the first thing to look at.
*/
static const unsigned long NBIOT_RESET_PULSE_MS = 200;

/*
   Whether a reset line was configured, as a value rather than a #ifdef, so that
   the ladder below can be read as ordinary code.
*/
#ifdef NBIOT_RESET_PIN
static const bool NBIOT_HAS_RESET_LINE = true;
#else
static const bool NBIOT_HAS_RESET_LINE = false;
#endif

/*
   SIM7028:n ohjaus SIMComin socket-AT-komennoilla:
     AT+NETOPEN                         activate the PDP context
     AT+NETOPEN?                        until state is 1, only then CIPOPEN
     AT+CIPOPEN=0,"UDP",,,<localPort>   UDP without a remote address
     AT+CIPSEND=0,<len>,"<ip>",<port>   answers ">", then the bytes as is

   CIPSEND takes the length up front and does not look for a terminator, so
   binary data needs no escaping. That is why a raw binary packet works on this
   module directly.

   NOTE: the AT sequence has not been verified against real hardware. It is
   based on the Waveshare wiki and the "SIM7028 Series TCPIP Application Note".
   If the module answers something else, check the serial log first.
*/
class Sim7028Transport : public Transport {
public:
  /*
     Lightweight detection for TRANSPORT_AUTO: is the modem attached and does it
     answer. Short timeouts, because this delays boot when no modem is present.

     Safe to call before begin(), and begin() reuses the same UART setup.
  */
  bool probe() {
    openUart();
    for (uint8_t attempt = 0; attempt < NBIOT_PROBE_ATTEMPTS; attempt++) {
      if (command("AT", "OK", NBIOT_PROBE_TIMEOUT_MS)) {
        return true;
      }
    }
    return false;
  }

  /*
     The steps are in initialiseModule(); this is here to carry the reason one
     of them gave up for over into what the display reads. setup() asks for it
     straight after calling begin(), so that a card that is not in its slot says
     so from boot rather than after the first send attempt a minute later.
  */
  bool begin() override {
    if (initialiseModule()) {
      return true;
    }
    failure = beginFailure;
    return false;
  }

  bool initialiseModule() {
    openUart();

#ifdef NBIOT_SERIAL_BRIDGE
    runSerialBridge();  // ei palaa
#endif

    /*
       Each step below replaces this with its own word before giving up, so that
       the display names the step rather than the whole sequence. "NO MODEM" for
       a SIM that was never inserted sends a reader to check the wiring, which is
       an afternoon under the wrong lid.
    */
    beginFailure = "NO MODEM";

    // The module may still be booting, so AT is attempted several times.
    bool awake = false;
    for (uint8_t attempt = 0; attempt < 10 && !awake; attempt++) {
      awake = command("AT", "OK", 1000);
    }
    if (!awake) {
      Serial.println("SIM7028: no answer to AT.");
      Serial.println("  Check: STA led lit (modem powered), TX/RX crossed,");
      Serial.println("  common GND, all jumper caps removed.");
      Serial.println("  To narrow it down, enable NBIOT_SERIAL_BRIDGE in config.h.");
      return false;
    }

    command("ATE0", "OK", 1000);       // echo off, otherwise responses get tangled
    command("AT+CMEE=2", "OK", 1000);  // verbose error messages in the log

    if (!unlockSim()) {
      return false;  // unlockSim() has set beginFailure to the reason
    }

    char cmd[96];

    /*
       The APN and the band lock are set with the radio off. AT+CGDCONT does not
       take effect mid-attach, so without the CFUN cycle the modem keeps
       searching with the old settings — which shows up as being stuck at
       "+CEREG: 0,2".
    */
    if (NBIOT_APN[0] != '\0' || NBIOT_BAND[0] != '\0') {
      command("AT+CFUN=0", "OK", 10000);

      if (NBIOT_BAND[0] != '\0') {
        snprintf(cmd, sizeof(cmd), "AT+CBAND=%s", NBIOT_BAND);
        if (!command(cmd, "OK", 5000)) {
          // Some firmware versions require the quotes, others reject them.
          snprintf(cmd, sizeof(cmd), "AT+CBAND=\"%s\"", NBIOT_BAND);
          command(cmd, "OK", 5000);
        }
      }

      if (NBIOT_APN[0] != '\0') {
        snprintf(cmd, sizeof(cmd), "AT+CGDCONT=1,\"IP\",\"%s\"", NBIOT_APN);
        if (!command(cmd, "OK", 5000)) {
          Serial.println("SIM7028: setting the APN failed");
          /*
             The radio is off at this point and has to be switched back on before
             giving up. Returning straight from here used to leave it off for
             good: the next begin() starts by turning it off again, so it never
             reached the CFUN=1 below and the device sat there off the air,
             saying NO MODEM about a modem that was answering every command.
          */
          beginFailure = "NO APN";
          command("AT+CFUN=1", "OK", 10000);
          return false;
        }
      }
    }

    command("AT+CFUN=1", "OK", 10000);

    /*
       Registration is not waited for here. It would block setup() for minutes,
       leaving the sensors, the display and BLE dark. The state is checked when
       sending instead.
    */
    initialised = true;
    return true;
  }

  bool send(const uint8_t *data, uint8_t length) override {
    char cmd[96];

    if (!initialised && !begin()) {
      return fail(beginFailure);
    }
    if (!ensureRegistered()) {
      return fail(detail);  // ensureRegistered() has filled in the numbers
    }

    if (!socketOpen) {
      if (!openNetwork()) {
        return false;
      }
      /*
         A UDP socket is opened with no remote address and only a local port —
         the manual's form is AT+CIPOPEN=<link>,"UDP",,,<localPort>. The
         destination is given in CIPSEND instead, unlike with TCP.
      */
      snprintf(cmd, sizeof(cmd), "AT+CIPOPEN=0,\"UDP\",,,%u",
               (unsigned)NBIOT_LOCAL_PORT);
      if (!command(cmd, "OK", 20000)) {
        Serial.println("SIM7028: opening the UDP socket failed");
        // The module may consider the socket open even when we do not — close it
        // to be safe so the next attempt starts from a clean slate.
        command("AT+CIPCLOSE=0", "OK", 10000);
        return fail("NO LINK");
      }
      socketOpen = true;
    }

    // With UDP the destination is given on every send.
    snprintf(cmd, sizeof(cmd), "AT+CIPSEND=0,%u,\"%s\",%u",
             (unsigned)length, SERVER_HOST, (unsigned)SERVER_PORT);
    resetBuffer();
#ifdef NBIOT_DEBUG
    Serial.printf("\n>> %s\n", cmd);
#endif
    Serial1.print(cmd);
    Serial1.print("\r\n");

    /*
       From here until the module acknowledges, it may be counting out the bytes
       it was promised. Remembered so that a failure can be undone rather than
       left for the next send to walk into.
    */
    outstanding = length;

    if (!waitFor(">", 5000)) {
      Serial.println("SIM7028: CIPSEND gave no prompt");
      return fail("NO PROMPT");
    }

    // Cleared before the payload goes out, not after: resetBuffer() now drops
    // whatever is waiting in the UART too, and after the write that could be
    // the acknowledgement itself.
    resetBuffer();
    Serial1.write(data, length);
    if (!waitFor("OK", 20000)) {
      Serial.println("SIM7028: send was not acknowledged");
      return fail("NO ACK");
    }

    outstanding = 0;
    consecutiveFailures = 0;
    // A send getting through is the only thing that answers the question the
    // restart depth was tracking, so it is the only thing that clears it.
    resetAttempts = 0;
    exhausted = false;
    failure = "";
    return true;
  }

  const char *name() const override {
    return "SIM7028 NB-IoT";
  }

  const char *lastFailure() const override {
    return failure;
  }

  bool needsHumanHelp() const override {
    return pinRejected;
  }

private:
  bool uartOpen = false;
  bool initialised = false;
  bool socketOpen = false;

  const char *failure = "";
  uint8_t consecutiveFailures = 0;

  /*
     Which step of begin() gave up, so that send() can report that rather than
     the blanket "NO MODEM" every one of them used to produce.
  */
  const char *beginFailure = "NO MODEM";

  /*
     A failure with numbers in it, for the cases where the code alone does not
     say what to do. Built here rather than pointed at a literal, and a member so
     that it outlives the call that fills it.

     21 characters is what the display's bottom row holds, and the elapsed time
     takes some of those, which is why these read "NO NET s2 q99" rather than
     anything more explanatory.
  */
  char detail[16] = "";

  /*
     Set when the card has refused the configured PIN. A card allows three
     attempts before it wants the PUK instead, and begin() runs again every
     retry interval, so without this latch a wrong digit in config.h would work
     its way through all three in a couple of minutes.
  */
  bool pinRejected = false;

  /*
     How many restarts have been spent since the last send got through, which is
     what decides how deep the next one goes. Counted across the whole outage
     rather than per ladder climb: the question the depth answers is "has a
     restart already failed to fix this", and that is not a question the cheaper
     rungs get to reset.
  */
  uint8_t resetAttempts = 0;

  /*
     Set when the resets have run out. Not a failure of its own — the link was
     already failing — but the point past which the firmware knows it is not
     going to be the one to fix this.
  */
  bool exhausted = false;

  /*
     How many payload bytes the module may still be waiting for. Zero when the
     command channel is known to be clear.
  */
  uint8_t outstanding = 0;

  /**
     Records why this send failed and puts the module back into a state the next
     one can start from.

     This is the fix for a failure that used to be permanent. AT+CIPSEND takes
     the length up front and then swallows exactly that many bytes, whatever they
     are. If the prompt is missed — one slow answer during a network hiccup is
     enough — the module is left counting, and the next cycle's "AT+CIPSEND=..."
     is fed to it as the payload. It duly sends that text to the server, which
     reports an unknown protocol version 65: the letter A. Nothing ever recovers,
     because every attempt feeds the mouth it is trying to talk to.
  */
  bool fail(const char *reason) {
    failure = reason;
    socketOpen = false;
    consecutiveFailures++;

    resynchronise();

    /*
       Escalating, because the cheap remedies work for the common causes and the
       expensive ones are worth reaching for only when they do not. A socket that
       is merely stale is fixed by reopening; a module that has lost its way needs
       to be restarted, which costs a minute of registration.
    */
    if (consecutiveFailures >= NBIOT_RESET_AFTER_FAILURES) {
      Serial.printf("SIM7028: %u failures in a row, restarting the module\n",
                    consecutiveFailures);
      restartModule();
      initialised = false;
      consecutiveFailures = 0;
    } else if (consecutiveFailures >= NBIOT_REOPEN_AFTER_FAILURES) {
      command("AT+CIPCLOSE=0", "OK", 10000);
      command("AT+NETCLOSE", "OK", 20000);
    }

    /*
       Said last, so that it replaces whichever step happened to fail this time.
       Once the resets are exhausted the step no longer tells a reader anything
       they can act on, and the one thing they can act on is a row of the display
       spent asking for it.
    */
    if (exhausted) {
      failure = "CUT POWER";
    }
    return false;
  }

  /*
     Restarts the module, going deeper each time this is reached without a send
     having succeeded in between.

     The first two are worth trying because a module can be stuck in ways it can
     still be talked out of. The third is not a third remedy: it is the point at
     which the firmware has spent everything it has, and the display stops
     naming steps and starts asking for the plug. The command still goes out
     each time, because it costs nothing and the wedge might yet clear.
  */
  void restartModule() {
    resetAttempts++;

    /*
       Ordered by how little each one needs from the module. AT+CRESET asks it
       politely. The reset line does not ask at all — it is the only rung here
       that works on a module that has stopped listening, which is why it is
       worth a wire. AT&F with AT+CFUN=1,1 asks again, but for something
       different: a stored command profile that may itself be the fault, and
       which no reset of any kind would clear.

       Without a wire the middle rung is simply absent and the ladder is one
       shorter, so the display reaches CUT POWER sooner. That is the honest
       outcome: there is genuinely less that can be done.
    */
    const uint8_t lastRung = NBIOT_HAS_RESET_LINE ? 3 : 2;

    if (resetAttempts == 1) {
      Serial.println("SIM7028: restarting the module (AT+CRESET)");
      command("AT+CRESET", "OK", 10000);
    } else if (NBIOT_HAS_RESET_LINE && resetAttempts == 2) {
      Serial.println("SIM7028: CRESET did not fix it, pulsing the reset line");
      pulseResetLine();
    } else {
      if (resetAttempts < lastRung) {
        Serial.println("SIM7028: trying a deeper reset");
      } else if (resetAttempts == lastRung) {
        Serial.println("SIM7028: last reset this firmware can reach");
      } else if (!exhausted) {
        Serial.println("SIM7028: every reset within reach has been tried.");
        Serial.println("  What is left is taking the module's power away, and");
        Serial.println("  no wire here can do that. Still trying, but somebody");
        Serial.println("  has to come.");
        exhausted = true;
      }
      deepReset();
      // Free, needs nothing from the module, and does nothing without a wire.
      pulseResetLine();
    }

    waitForModule();
  }

  /*
     AT&F throws away a command profile that may itself be what is wrong. Safe
     here because begin() sets everything it needs again straight afterwards,
     and because the module's factory baud rate is the same NBIOT_BAUD the UART
     is already open at, so the two cannot end up talking past each other.

     Then 3GPP's own reset, in case the vendor's is the part that is stuck. A
     module that does not know the command answers ERROR and nothing is lost.
  */
  void deepReset() {
    command("AT&F", "OK", 5000);
    command("AT+CFUN=1,1", "OK", 10000);
  }

  /*
     Holds the HAT's reset line asserted for a moment.

     Active high, and driven push-pull, which is the opposite of what a bare
     SIMCom reset pin wants: the HAT puts the header pin on the base of an NPN
     whose collector holds the module's own reset, so the transistor inverts and
     driving this pin high is what pulls the module's line low. The 47k on the
     base also decides how this fails — an undriven pin leaves the module
     running, so a Pico that is itself resetting does not take the modem with it.
  */
  void pulseResetLine() {
#ifdef NBIOT_RESET_PIN
    Serial.println("SIM7028: asserting the reset line");
    digitalWrite(NBIOT_RESET_PIN, HIGH);
    idleFor(NBIOT_RESET_PULSE_MS);
    digitalWrite(NBIOT_RESET_PIN, LOW);
#endif
  }

  /*
     Waits for a restarted module to answer AT again.

     The waiting is not what this is for — the next send is a minute away and
     would have waited anyway. It is so that "the reset worked and the module is
     back" and "the module never came back" are two different lines in the log
     instead of the same silence.
  */
  bool waitForModule() {
    unsigned long start = millis();
    while (millis() - start < NBIOT_RESET_RECOVERY_MS) {
      idleFor(1000);
      if (commandModeRestored()) {
        Serial.printf("SIM7028: answering again %lu s after the reset\n",
                      (millis() - start) / 1000);
        return true;
      }
    }
    Serial.printf("SIM7028: no answer %lu s after the reset\n",
                  NBIOT_RESET_RECOVERY_MS / 1000);
    return false;
  }

  /*
     Gets the module talking again, whatever it was doing.

     First politely: if it answers AT with OK it is in command mode and there is
     nothing to undo. If it does not, it is almost certainly still counting out a
     payload — so it is given exactly the bytes it is owed, which completes that
     send and returns it to command mode. Zeroes, because the server recognises
     the version byte and drops the packet with one line in its log; a second
     copy of a real measurement would be worse.
  */
  void resynchronise() {
    if (commandModeRestored()) {
      outstanding = 0;
      return;
    }
    if (outstanding > 0) {
      Serial.printf("SIM7028: feeding %u byte(s) the module was still owed\n",
                    outstanding);
      resetBuffer();  // before the write, for the reason given at resetBuffer()
      for (uint8_t i = 0; i < outstanding; i++) {
        Serial1.write((uint8_t)0);
      }
      outstanding = 0;
      waitFor("OK", 10000);
      commandModeRestored();
    }
  }

  /** Whether the module answers a plain AT, which only command mode does. */
  bool commandModeRestored() {
    for (uint8_t attempt = 0; attempt < 3; attempt++) {
      if (command("AT", "OK", 1000)) {
        return true;
      }
    }
    return false;
  }

  /** Idempotent so that probe() and begin() can both call it. */
  void openUart() {
    if (uartOpen) {
      return;
    }
    Serial1.setTX(NBIOT_TX_PIN);
    Serial1.setRX(NBIOT_RX_PIN);
    Serial1.begin(NBIOT_BAUD);
#ifdef NBIOT_RESET_PIN
    /*
       Driven low from the start rather than left floating. The base pull-down
       would hold it there anyway, but a line that resets the modem is worth
       being certain about rather than trusting to a resistor and the weather.
    */
    pinMode(NBIOT_RESET_PIN, OUTPUT);
    digitalWrite(NBIOT_RESET_PIN, LOW);
#endif
    uartOpen = true;
  }
  char buffer[192];
  size_t used = 0;

  /*
     Clears both halves of the conversation: what has been read, and what is
     still sitting in the UART waiting to be.

     The second half is the one that matters. Anything the module said that
     nobody collected — the tail of a response a timeout walked away from, the
     rest of a line after an ERROR, an unsolicited +CEREG: as it loses the
     network — stays in the receive buffer and is read back as the answer to
     whatever is asked next. One such leftover puts the whole exchange a reply
     out of step, and it stays there: every command is then answered by the one
     before it, every answer looks wrong, and nothing in the sequence ever puts
     it right. The module is fine and the link never works again.
  */
  void resetBuffer() {
    while (Serial1.available() > 0) {
      Serial1.read();
    }
    used = 0;
    buffer[0] = '\0';
  }

  /*
     Reads the UART until the token appears in the response, the module reports
     an error, or time runs out. The buffer slides so a long response cannot
     overflow it.
  */
  bool waitFor(const char *token, unsigned long timeoutMs) {
    unsigned long start = millis();
    while (millis() - start <= timeoutMs) {
      while (Serial1.available() > 0) {
        if (used + 1 >= sizeof(buffer)) {
          size_t keep = sizeof(buffer) / 2;
          memmove(buffer, buffer + (used - keep), keep);
          used = keep;
        }
        char c = (char)Serial1.read();
        buffer[used++] = c;
        buffer[used] = '\0';
#ifdef NBIOT_DEBUG
        Serial.write(c);
#endif

        if (strstr(buffer, token) != nullptr) {
          return true;
        }
        if (strstr(buffer, "ERROR") != nullptr) {
          Serial.printf("SIM7028: %s\n", buffer);
          return false;
        }
      }
      transportIdle();
    }
    return false;
  }

  bool command(const char *cmd, const char *token, unsigned long timeoutMs) {
    resetBuffer();
#ifdef NBIOT_DEBUG
    Serial.printf("\n>> %s\n", cmd);
#endif
    Serial1.print(cmd);
    Serial1.print("\r\n");
    return waitFor(token, timeoutMs);
  }

  /*
     SIM PIN entry. Only affects registration — a locked SIM answers AT commands
     normally, so this is never the reason a module does not respond at all.

     AT+CPIN? returns "+CPIN: READY" if the card is unlocked or already opened,
     and "+CPIN: SIM PIN" if a code is expected.
  */
  bool unlockSim() {
    /*
       Not tried again once the card has said no. Three refusals and it wants
       the PUK instead, and this runs on every retry, so the only safe number of
       attempts per power cycle is one.
    */
    if (pinRejected) {
      beginFailure = "BAD PIN";
      return false;
    }

    if (!command("AT+CPIN?", "OK", 5000)) {
      Serial.println("SIM7028: AT+CPIN? failed, is the SIM inserted?");
      beginFailure = "NO SIM";
      return false;
    }
    if (strstr(buffer, "READY") != nullptr) {
      return true;
    }
    /*
       Its own state, and its own word on the display, because it is the one
       nothing here can undo: the card has had its three attempts and wants the
       PUK now, which means a phone and the letter it came in.
    */
    if (strstr(buffer, "SIM PUK") != nullptr) {
      Serial.println("SIM7028: the card is behind its PUK. Unlock it in a phone.");
      pinRejected = true;
      beginFailure = "SIM PUK";
      return false;
    }
    if (strstr(buffer, "SIM PIN") == nullptr) {
      Serial.printf("SIM7028: unexpected CPIN state: %s\n", buffer);
      beginFailure = "SIM ???";
      return false;
    }
    if (NBIOT_SIM_PIN[0] == '\0') {
      Serial.println("SIM7028: the card asks for a PIN but NBIOT_SIM_PIN is empty");
      pinRejected = true;  // nothing to try, and nothing that waiting will change
      beginFailure = "NO PIN";
      return false;
    }

    char cmd[32];
    snprintf(cmd, sizeof(cmd), "AT+CPIN=\"%s\"", NBIOT_SIM_PIN);
    if (!command(cmd, "OK", 10000)) {
      Serial.println("SIM7028: PIN rejected, CHECK THE CODE before trying again");
      Serial.println("  Not trying it again by itself: two attempts are left");
      Serial.println("  before the card wants the PUK.");
      pinRejected = true;
      beginFailure = "BAD PIN";
      return false;
    }

    // Unlocking is not instant; the card initialises for a few seconds.
    unsigned long start = millis();
    while (millis() - start < 10000) {
      if (command("AT+CPIN?", "OK", 3000) && strstr(buffer, "READY") != nullptr) {
        return true;
      }
      idleFor(1000);
    }
    Serial.println("SIM7028: SIM did not become ready after the PIN");
    beginFailure = "SIM SLOW";
    return false;
  }

#ifdef NBIOT_SERIAL_BRIDGE
  /*
     Forwards between the serial console and the modem in both directions. The
     point is to isolate a wiring problem: if AT gets no answer here, the fault
     is in the wiring or the power supply rather than the software.
  */
  void runSerialBridge() {
    unsigned long start = millis();
    while (!Serial && millis() - start < 5000) {
      // wait for the USB serial port so the instructions are not missed
    }
    Serial.println("SIM7028 bridge mode. Type AT and press enter.");
    Serial.println("Serial monitor line ending: Both NL & CR.");
    for (;;) {
      while (Serial.available() > 0) {
        Serial1.write(Serial.read());
      }
      while (Serial1.available() > 0) {
        Serial.write(Serial1.read());
      }
    }
  }
#endif

  /*
     +CEREG: <n>,<stat>. A working NB-IoT attach typically takes 5-20 seconds,
     so state 2 persisting means a real problem rather than slowness.
  */
  int registrationStatus() {
    if (!command("AT+CEREG?", "OK", 5000)) {
      return -1;
    }
    const char *cereg = strstr(buffer, "+CEREG:");
    const char *comma = cereg != nullptr ? strchr(cereg, ',') : nullptr;
    if (comma == nullptr) {
      return -1;
    }
    return comma[1] - '0';
  }

  static const char *describeRegistration(int stat) {
    switch (stat) {
      case 0: return "not registered, not searching";
      case 1: return "registered, home network";
      case 2: return "searching for an operator";
      case 3: return "registration denied";
      case 4: return "unknown, usually no coverage";
      case 5: return "registered, roaming";
      default: return "no answer";
    }
  }

  void idleFor(unsigned long milliseconds) {
    unsigned long start = millis();
    while (millis() - start < milliseconds) {
      transportIdle();
    }
  }

  /*
     Starts the socket service, that is, activates the PDP context.

     AT+NETOPEN acknowledges with OK immediately, but the context activates only
     afterwards and the result arrives as a separate "+NETOPEN: <err>" line. If
     CIPOPEN is sent before that it fails, because the context is not open yet.
     Hence the state is polled with AT+NETOPEN? until it reads 1.
  */
  bool openNetwork() {
    // It may already be open, in which case the answer is "+IP ERROR: Network
    // is already opened". The result is ignored and the state checked instead.
    command("AT+NETOPEN", "OK", 10000);

    unsigned long start = millis();
    while (millis() - start < NBIOT_NETOPEN_TIMEOUT_MS) {
      if (command("AT+NETOPEN?", "OK", 5000) && netOpenState() == 1) {
        return true;
      }
      idleFor(2000);
    }

    Serial.println("SIM7028: the PDP context did not activate");
    command("AT+CGPADDR", "OK", 5000);  // did the device get an IP address at all
    return false;
  }

  /*
     The number of spaces after the colon varies between firmware versions, so
     the digit is searched for rather than assumed.
  */
  int netOpenState() {
    const char *tag = strstr(buffer, "+NETOPEN");
    if (tag == nullptr) {
      return -1;
    }
    for (const char *p = tag + 8; *p != '\0' && p < tag + 16; p++) {
      if (*p >= '0' && *p <= '9') {
        return *p - '0';
      }
    }
    return -1;
  }

  bool ensureRegistered() {
    int stat = registrationStatus();
    if (stat == 1 || stat == 5) {
      return true;
    }
    Serial.printf("SIM7028: not on the network (CEREG stat %d: %s)\n",
                  stat, describeRegistration(stat));

    /*
       Both numbers go on the display, because "NO NET" on its own is the one
       failure that still leaves the reader guessing, and these two split the
       guesses apart: q99 with any state is no signal being measured at all,
       which is a detached antenna and a job for a screwdriver, while a decent
       q with s2 is a modem that can hear the network and is not being let on,
       which is a job for the operator. The same walk to the shed, but not the
       same thing to take along.
    */
    int rssi = signalQuality();
    snprintf(detail, sizeof(detail), "NO NET s%d q%d", stat, rssi);

    logDiagnostics();
    socketOpen = false;
    return false;
  }

  /*
     +CSQ: <rssi>,<ber>. 99 means "not known or not detectable", and is what a
     modem with no antenna reports.
  */
  int signalQuality() {
    if (!command("AT+CSQ", "OK", 5000)) {
      return 99;
    }
    const char *csq = strstr(buffer, "+CSQ:");
    if (csq == nullptr) {
      return 99;
    }
    return atoi(csq + 5);
  }

  /*
     Only printed when attaching fails, and after signalQuality() has already
     asked for the signal — what is left is who the modem thinks it should be
     talking to, and whether the card is still with us.
  */
  void logDiagnostics() {
    command("AT+COPS?", "OK", 10000);
    command("AT+CPIN?", "OK", 5000);
  }
};

#endif  // USE_NBIOT
