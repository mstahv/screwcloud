#!/usr/bin/env bash
#
# Verifies that the two firmware variants agree on the wire format.
#
# Protocol.h is duplicated rather than shared because the Arduino build does not
# reliably resolve includes that reach outside a sketch folder — arduino-cli
# copies the sketch directory into its build path, so a "../shared/Protocol.h"
# include can break depending on the toolchain version. A byte-identical copy
# plus this check is more robust than an include that might silently stop
# working.
#
# The Ruuvi Data Format 5 constants are checked too. That format is externally
# fixed and will not change, but a typo in one copy would produce silently wrong
# temperatures from one board only — exactly the kind of bug that takes days to
# notice.
#
# Only the constants both variants must share are checked. The ESP32 variant
# deliberately decodes fewer fields (no pressure, acceleration or battery), so
# the files are not expected to match line for line.
#
# Run from the repository root:
#     tools/check-protocol-sync.sh

set -uo pipefail

PICO_DIR=temperature-reader
ESP_DIR=esp32-s3-reader
PICO_SKETCH="$PICO_DIR/temperature-reader.ino"
ESP_SKETCH="$ESP_DIR/esp32-s3-reader.ino"
status=0

echo "== Protocol.h: the wire format to the server =="
if diff -u "$PICO_DIR/Protocol.h" "$ESP_DIR/Protocol.h"; then
    echo "OK: byte identical"
else
    echo "FAIL: the wire format definitions have diverged (see the diff above)"
    status=1
fi

echo
echo "== Ruuvi Data Format 5: shared constants =="

# Each entry must appear verbatim in both sketches.
required=(
    'RUUVI_COMPANY_ID = 0x0499'
    'RUUVI_FORMAT_5 = 0x05'
    'RUUVI_FORMAT_5_LEN = 24'
    'temperature = rawTemperature * 0.005f'
    'humidity = rawHumidity * 0.0025f'
    'rawTemperature != (int16_t)0x8000'
    'rawHumidity != 0xFFFF'
    'memcpy(mac, &data[18], sizeof(mac))'
)

for token in "${required[@]}"; do
    in_pico=no
    in_esp=no
    grep -qF -- "$token" "$PICO_SKETCH" && in_pico=yes
    grep -qF -- "$token" "$ESP_SKETCH" && in_esp=yes

    if [ "$in_pico" = yes ] && [ "$in_esp" = yes ]; then
        printf 'OK    %s\n' "$token"
    else
        printf 'FAIL  %s  (pico=%s esp32=%s)\n' "$token" "$in_pico" "$in_esp"
        status=1
    fi
done

echo
if [ $status -eq 0 ]; then
    echo "All checks passed."
else
    echo "Checks failed: the variants no longer agree on the format."
fi
exit $status
