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
      return false;
    }
    if (udp.beginPacket(SERVER_HOST, SERVER_PORT) != 1) {
      return false;
    }
    udp.write(data, length);
    return udp.endPacket() == 1;
  }

  const char *name() const override {
    return "WiFi";
  }

private:
  WiFiUDP udp;

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

  bool begin() override {
    openUart();

#ifdef NBIOT_SERIAL_BRIDGE
    runSerialBridge();  // ei palaa
#endif

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
      return false;
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
      return false;
    }
    if (!ensureRegistered()) {
      return false;
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
        return false;
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
    if (!waitFor(">", 5000)) {
      Serial.println("SIM7028: CIPSEND gave no prompt");
      socketOpen = false;
      return false;
    }

    Serial1.write(data, length);
    resetBuffer();
    if (!waitFor("OK", 20000)) {
      Serial.println("SIM7028: send was not acknowledged");
      socketOpen = false;
      return false;
    }
    return true;
  }

  const char *name() const override {
    return "SIM7028 NB-IoT";
  }

private:
  bool uartOpen = false;
  bool initialised = false;
  bool socketOpen = false;

  /** Idempotent so that probe() and begin() can both call it. */
  void openUart() {
    if (uartOpen) {
      return;
    }
    Serial1.setTX(NBIOT_TX_PIN);
    Serial1.setRX(NBIOT_RX_PIN);
    Serial1.begin(NBIOT_BAUD);
    uartOpen = true;
  }
  char buffer[192];
  size_t used = 0;

  void resetBuffer() {
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
    if (!command("AT+CPIN?", "OK", 5000)) {
      Serial.println("SIM7028: AT+CPIN? failed, is the SIM inserted?");
      return false;
    }
    if (strstr(buffer, "READY") != nullptr) {
      return true;
    }
    if (strstr(buffer, "SIM PIN") == nullptr) {
      Serial.printf("SIM7028: unexpected CPIN state: %s\n", buffer);
      return false;
    }
    if (NBIOT_SIM_PIN[0] == '\0') {
      Serial.println("SIM7028: the card asks for a PIN but NBIOT_SIM_PIN is empty");
      return false;
    }

    char cmd[32];
    snprintf(cmd, sizeof(cmd), "AT+CPIN=\"%s\"", NBIOT_SIM_PIN);
    if (!command(cmd, "OK", 10000)) {
      // A wrong PIN burns attempts and locks the card behind the PUK, so this
      // is not worth retrying.
      Serial.println("SIM7028: PIN rejected, CHECK THE CODE before trying again");
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
    logDiagnostics();
    socketOpen = false;
    return false;
  }

  /*
     Only printed when attaching fails. CSQ separates an antenna problem from a
     network problem: 99,99 means no signal is measured at all, which is almost
     always a detached or unscrewed antenna.
  */
  void logDiagnostics() {
    command("AT+CSQ", "OK", 5000);
    command("AT+COPS?", "OK", 10000);
    command("AT+CPIN?", "OK", 5000);
  }
};

#endif  // USE_NBIOT
