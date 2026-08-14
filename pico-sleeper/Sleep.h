#pragma once

#include <Arduino.h>

/*
  Waiting between readings, with as little of the board awake as can be arranged.

  Since pico-sdk 2.3.0 the SDK carries a library for exactly this — pico_low_power,
  with official examples and, better, measured numbers. Raspberry Pi's own figures
  for a Pico 2 running from 3V3:

    sleep                  6.9 mA
    dormant                3.7 mA
    Pstate, SRAM0 on       0.14 mA
    Pstate, all SRAM off   0.08 mA

  This uses **sleep**, the lightest of the three, because it is the only one that
  keeps the program running: it returns where it was called, so the sketch above
  stays the ordinary loop it looks like. It stops the processor and most clocks
  and wakes on the timer, which is a long way below waiting in a delay() — that
  halts the core on a WFE but leaves every clock and PLL running.

  Dormant and Pstate are the real prizes and neither is free:

  - **Dormant** stops the crystal oscillator. USB goes down with it, so the serial
    log — the instrument this whole experiment is being run with — stops.
  - **Pstate** switches power domains off and **restarts the program on waking**.
    That suits this device better than it sounds, since a wake is already "run
    once from the top", but it needs the sequence number moved into a POWMAN
    scratch register to survive, and it takes the serial connection with it too.

  And a caveat that applies to all of them, worth stating before anyone plans a
  battery: those numbers are for a **Pico 2, not a Pico 2 W**. The CYW43 radio
  chip is powered separately and does not appear in them at all. On this board it
  may well be the largest consumer between readings, in which case the difference
  between 6.9 mA and 0.14 mA of processor is not the difference it looks like.
  That is a thing to measure with a meter, not to reason about here.
*/

/*
   pico_low_power arrived in pico-sdk 2.3.0, which arduino-pico bundles from
   5.7.0 onwards. Older cores compile the fallback instead.

   If the link fails with an undefined reference to low_power_sleep_until_default_timer,
   the core has the header but does not build that library: set this to 0 and the
   sketch goes back to delay(), which costs power and nothing else.
*/
#ifndef SLEEP_USE_SDK_LOW_POWER
#if defined(__has_include)
#if __has_include(<pico/low_power.h>)
#define SLEEP_USE_SDK_LOW_POWER 1
#endif
#endif
#endif

#ifndef SLEEP_USE_SDK_LOW_POWER
#define SLEEP_USE_SDK_LOW_POWER 0
#endif

#if SLEEP_USE_SDK_LOW_POWER
#include <pico/low_power.h>
#endif

class Sleep {
public:
  static void until(unsigned long milliseconds) {
#if SLEEP_USE_SDK_LOW_POWER
    /*
       Not exclusive: other interrupts stay live, which is what keeps the USB
       serial connection alive across the wait. An experiment that cannot be
       watched is not much of an experiment.
    */
    if (low_power_sleep_for_ms(milliseconds, NULL, false) == 0) {
      return;
    }
    // Whatever the reason, waiting badly beats not waiting at all.
    Serial.println("low_power_sleep_for_ms failed, falling back to delay()");
#endif
    busyWait(milliseconds);
  }

  static const char *description() {
#if SLEEP_USE_SDK_LOW_POWER
    return "pico_low_power sleep";
#else
    return "delay()";
#endif
  }

private:
  /*
     Waits in short steps rather than one long one. Nothing needs servicing here
     today, but a watchdog would, and a step short enough to feed one costs
     nothing to have from the start. Each step is a WFE halt rather than a spin,
     so this is idle rather than busy — just with every clock still running.
  */
  static void busyWait(unsigned long milliseconds) {
    const unsigned long STEP_MS = 1000;
    unsigned long start = millis();
    while (millis() - start < milliseconds) {
      unsigned long remaining = milliseconds - (millis() - start);
      delay(remaining < STEP_MS ? remaining : STEP_MS);
    }
  }
};
