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
    if (udp.beginPacket(SERVER_HOST, SERVER_PORT) != 1) {
      failure = "no host";
      return false;
    }
    udp.write(data, length);
    if (udp.endPacket() != 1) {
      failure = "send failed";
      return false;
    }
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
