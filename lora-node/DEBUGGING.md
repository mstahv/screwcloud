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

## RadioLib's chip detection cannot be trusted to have run

```cpp
bool LR11x0::findChip(uint8_t ver) {
  int16_t state = getVersionInfo(&info);
  RADIOLIB_ASSERT(state);     // expands to: if(state != 0) return(state);
```

`findChip` returns `bool`, and the macro returns the error code from it. A failed
version read therefore comes back as `(bool)(-707)`, which is **true** — "chip
found". Everything `begin()` does afterwards is built on a detection that never
happened, and every conclusion drawn from "well, it found the chip" is worthless.

So the sketch reads the version **by hand**, with no library between it and the
wires, and prints the raw bytes. Two out, five back, no configuration needed, and
the answer is known: the module on the Raspberry Pi reports hardware `0x22`,
device `0x03`, firmware 1.1.

| Raw bytes | Meaning |
|---|---|
| all `00` | MISO is not arriving, or the module has no power |
| all `FF` | MISO is floating high — the same fault, other polarity |
| sensible values | the wiring is good and the fault is above it |

## Is it in bootloader mode?

The first thing the sketch prints after a failure, because it explains a shape
of failure that nothing else does: **a chip in bootloader mode answers a version
query and refuses everything else.**

| `device` | Meaning |
|---|---|
| `0x03` | LR1121, normal mode |
| `0xDF` | bootloader — no working firmware, only firmware updates accepted |

RadioLib's `findChip()` treats a bootloader as found, so `begin()` gets past chip
detection and then fails on the first real command — with `getErrors()` failing
too, since that is a real command as well.

If it says `0xDF`, no amount of wiring or timing will help. The demo package
ships `lr1121_firmware_update` with three transceiver images, and RadioLib has
`updateFirmware()`.

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

`getErrors()` is protected in RadioLib, so the sketch declares a small subclass
that publishes it. The library also has a `RADIOLIB_GODMODE` build flag that
opens everything at once, but a flag is a thing to remember and undo, and a
three line subclass is not.

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
