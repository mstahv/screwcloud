#pragma once

#include <Arduino.h>

/*
  Waiting between readings, with as little of the board awake as can be arranged.

  Read this before believing the name: **this is not dormant mode.** What it does
  is switch the radio off (the transport's end(), called before this) and then
  wait. On this board that is most of the win — the CYW43 draws more idle than the
  RP2350 does — but it is milliamps, not microamps, and the difference matters if
  this is ever to run on a battery.

  Why it is not more than that, written down so nobody has to rediscover it:

  - The Arduino core for this chip documents **no sleep API at all**. Its RP2040
    helper class offers the clock frequency, the watchdog and the reset reason,
    and nothing about sleeping.
  - The Pico SDK's `pico_sleep` (dormant, wake on an alarm) lives in **pico-extras**,
    a repository the Arduino core does not bundle.
  - The RP2350 has a power manager with an always-on timer that can wake it from
    a genuinely low power state, which is the right answer here — but it is
    SDK-level work whose entry points want verifying against the core actually
    installed, not guessing at.
  - The RP2040's RTC, which most sleep examples on the internet use, **does not
    exist on the RP2350**. Half of what is written about sleeping on a Pico does
    not apply to this board.

  So this is the honest baseline: correct, certain to compile, and measurable. The
  sketch prints how long each wake took, which is the number that decides whether
  a deeper sleep is worth the work — a wake of eight seconds every fifteen minutes
  is a duty cycle of about one percent, and at that point the sleeping current is
  what the battery life is made of.
*/
class Sleep {
public:
  /*
     Waits in short steps rather than one long one. Nothing needs servicing here
     today, but a watchdog would, and a step short enough to feed one costs
     nothing to have from the start.
  */
  static void until(unsigned long milliseconds) {
    const unsigned long STEP_MS = 1000;
    unsigned long start = millis();
    while (millis() - start < milliseconds) {
      unsigned long remaining = milliseconds - (millis() - start);
      delay(remaining < STEP_MS ? remaining : STEP_MS);
    }
  }
};
