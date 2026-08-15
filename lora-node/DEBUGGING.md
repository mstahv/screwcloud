# When the radio will not start

RadioLib reports a number and nothing else. The numbers matter, and so does the
one thing they cannot tell you: which command the chip refused.

## The numbers

| Code | Name | What it points at |
|---|---|---|
| `-2` | `ERR_CHIP_NOT_FOUND` | SPI, or the chip has no power. The version could not be read at all |
| `-705` | `ERR_SPI_CMD_TIMEOUT` | The BUSY line. The chip never said it was ready |
| `-706` | `ERR_SPI_CMD_INVALID` | The chip did not recognise the command |
| `-707` | `ERR_SPI_CMD_FAILED` | The chip took the command and could not carry it out |

`-707` is the interesting one, because it means **everything electrical is
working**: SPI in both directions, the busy line and reset. RadioLib's `begin()`
reads the chip's version before it configures anything, so a `-707` is proof the
chip is there and talking.

## Asking the chip

The sketch reads the chip's own error register after a failed `begin()` and
prints the bits by name. That register says what an error code cannot: which of
the blocks it calibrates on the way up refused, and whether either oscillator
started.

| Bit | Name | Meaning |
|---|---|---|
| 0 | `LF_RC_CALIB` | the low frequency RC oscillator would not calibrate |
| 1 | `HF_RC_CALIB` | the high frequency RC oscillator would not calibrate |
| 2 | `ADC_CALIB` | the converter would not calibrate |
| 3 | `PLL_CALIB` | the synthesiser would not calibrate |
| 4 | `IMG_CALIB` | image rejection would not calibrate |
| 5 | `HF_XOSC_START` | **the 32 MHz oscillator did not start — the TCXO** |
| 6 | `LF_XOSC_START` | the 32 kHz oscillator did not start |
| 7 | `PLL_LOCK` | the synthesiser would not lock |

The chip has to be in standby to answer honestly: asked while it is busy it
reports nothing wrong, which is not the same as nothing being wrong.

## Finding out which command

`build_opt.h` in this folder turns on RadioLib's own tracing:

```
-DRADIOLIB_DEBUG_BASIC
```

The Arduino IDE passes the contents of that file to the compiler, so it is enough
to have it there — reflash and watch the monitor. RadioLib then prints the step
it is on, and after a failed calibration it reads the chip's own error register
and prints that too, which is the thing no error code carries.

For the full picture, every SPI transaction byte by byte:

```
-DRADIOLIB_DEBUG_BASIC
-DRADIOLIB_DEBUG_SPI
```

That is a lot of output at 115200 baud and worth turning off again afterwards,
but it settles arguments: you can see the command go out and the status come
back.

## The order to suspect things

1. **The oscillator.** This module has a 3.0 V TCXO, and RadioLib defaults to
   1.6 V, which is right for a different board. Wrong here means a chip that
   answers and cannot calibrate.
2. **Its settling time.** RadioLib calibrates 5 ms after powering the oscillator;
   the vendor's driver allows this one about 9 ms.
3. **The supply.** `3V3(OUT)` on a Pico is a regulator output, and calibration
   and transmission both draw far more than idle. A sagging rail fails in ways
   that look like configuration errors.
4. **The antenna switch table.** Wrong here is the quiet failure: everything
   reports success and nothing is radiated.
