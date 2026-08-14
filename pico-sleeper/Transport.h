#pragma once

#include <Arduino.h>
#include "config.h"

/*
  Getting one packet to the server, and nothing else.

  A separate interface for a sketch with one implementation, because the
  implementation is the part expected to change. LoRaWAN is the reason this
  device exists in the shape it does: a board that wakes, measures, sends a few
  dozen bytes and sleeps is exactly what LoRaWAN is for, and WiFi is here because
  it can be tested on a desk today.

  What a second implementation has to provide is on purpose almost nothing:

    begin()   once per wake, may take seconds and may fail
    send()    a packet of at most PROTOCOL_HEADER_SIZE + PROTOCOL_SENSOR_SIZE
              bytes, fire and forget
    end()     give up the radio before sleeping, which is where the power goes

  Two things a LoRaWAN implementation will not be able to keep, and both are
  written down here rather than discovered later:

  - **The packet will not fit as it is.** A LoRaWAN payload at the slowest data
    rates is 51 bytes, and duty cycle limits make a packet every fifteen minutes
    a serious portion of the budget. This device sends 16 bytes, which fits, but
    only because it carries one sensor.
  - **There is no session per wake.** Joining the network on every wake would
    cost more than it saves; the session has to survive sleep, which means either
    ABP or keeping the session keys somewhere that survives — a thing the sleep
    mode chosen here decides, not the transport.
*/
class Transport {
public:
  virtual ~Transport() {}

  /** Called after each wake, before sending. Failure is not fatal: the next wake tries again. */
  virtual bool begin() = 0;

  virtual bool send(const uint8_t *data, uint8_t length) = 0;

  /*
     Called before sleeping. The radio is the largest consumer on this board by a
     wide margin, so this is not a courtesy — it is most of the point.
  */
  virtual void end() = 0;

  virtual const char *name() const = 0;

  /** Why the last attempt failed, for the log. */
  virtual const char *lastFailure() const {
    return "";
  }
};

#include <WiFi.h>
#include <WiFiUdp.h>

/*
   How long the radio stays on after the packet has been handed over.

   Here rather than in config.h because it is not a property of anyone's network:
   it is how long a network stack needs to get a queued datagram out of the door.
   config.h is also gitignored, so a constant added there would fail to compile
   for everyone who already has one.
*/
static const unsigned long WIFI_LINGER_MS = 300;

/*
   WiFi and UDP, the same as the other readers in this repository: no reply to
   wait for, and a packet lost on the way is replaced by the next one.

   Connecting is by far the slowest part of a wake — seconds, against
   milliseconds for everything else — which is why the temperature is read before
   this is switched on rather than after.
*/
class WiFiTransport : public Transport {
public:
  bool begin() override {
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED) {
      if (millis() - start > WIFI_CONNECT_TIMEOUT_MS) {
        failure = "no WiFi";
        return false;
      }
      delay(50);
    }
    Serial.printf("WiFi: %s, %lu ms, RSSI %d dBm\n",
                  WiFi.localIP().toString().c_str(), millis() - start, WiFi.RSSI());
    failure = "";
    return true;
  }

  bool send(const uint8_t *data, uint8_t length) override {
    /*
       Resolved explicitly rather than letting beginPacket do it, for two
       reasons: a name that will not resolve is a different failure from a packet
       that will not send, and the address is worth printing when a packet has
       gone missing.
    */
    IPAddress server;
    if (!WiFi.hostByName(SERVER_HOST, server)) {
      failure = "no DNS";
      return false;
    }

    if (udp.beginPacket(server, SERVER_PORT) != 1) {
      failure = "no socket";
      return false;
    }
    udp.write(data, length);
    if (udp.endPacket() != 1) {
      failure = "send failed";
      return false;
    }

    /*
       endPacket() means the datagram was handed to the network stack, not that
       it has left the antenna. A first packet on a fresh connection usually
       waits for an ARP reply before it can go anywhere, and powering the radio
       down a millisecond later kills it in the queue — which is exactly what a
       "sent" in the log with nothing at the server looks like.

       There is no acknowledgement to wait for in UDP, so waiting a moment is
       the honest remedy. It costs a fraction of the seconds the connection
       itself took.
    */
    delay(WIFI_LINGER_MS);

    Serial.printf("Sent %u bytes to %s (%s):%u\n",
                  length, SERVER_HOST, server.toString().c_str(), (unsigned)SERVER_PORT);
    failure = "";
    return true;
  }

  /*
     Disconnect and power the radio down. WiFi.end() takes the CYW43 with it,
     which is the difference between a board drawing tens of milliamps between
     readings and one drawing a fraction of that.
  */
  void end() override {
    WiFi.disconnect(true);
    WiFi.end();
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
};
