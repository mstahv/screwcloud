package org.vaadin.example.ruuvi;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Ruuvi's <a href="https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2">Data
 * Format 5 (RAWv2)</a>, the payload of a RuuviTag's BLE advertisement.
 *
 * <p>Nothing connects to a tag: the whole measurement sits in the
 * manufacturer-specific field of a broadcast, under Ruuvi's company identifier.
 * BlueZ hands that field over with the company id already stripped, so the array
 * this receives starts at the format byte.
 *
 * <p>Every field has its own value meaning "not available", and each one becomes
 * null rather than a plausible-looking number. The scalings are fixed by the
 * format and are shared with both firmwares — {@code ProtocolSyncTest} checks that
 * they still agree.
 */
public final class DataFormat5 {

    /** Ruuvi Innovations Ltd, as registered with the Bluetooth SIG. */
    public static final int COMPANY_ID = 0x0499;

    static final int FORMAT = 0x05;

    /** Bytes after the company id. */
    static final int LENGTH = 24;

    private static final int TEMPERATURE_INVALID = (short) 0x8000;
    private static final int UINT16_INVALID = 0xFFFF;
    private static final int BATTERY_INVALID = 0x7FF;
    private static final int TX_POWER_INVALID = 0x1F;

    private DataFormat5() {
    }

    /**
     * @param data the manufacturer-specific payload, starting at the format byte
     * @param at   when it was heard
     * @param rssi signal strength, or null if the receiver did not report one
     * @return the reading, or empty if this is not a Data Format 5 advertisement
     */
    public static Optional<RuuviReading> parse(byte[] data, Instant at, Short rssi) {
        if (data == null || data.length < LENGTH || (data[0] & 0xFF) != FORMAT) {
            return Optional.empty();
        }

        int rawTemperature = readInt16(data, 1);
        Double temperature = rawTemperature == TEMPERATURE_INVALID
                ? null : rawTemperature * 0.005;

        int rawHumidity = readUint16(data, 3);
        Double humidity = rawHumidity == UINT16_INVALID ? null : rawHumidity * 0.0025;

        int rawPressure = readUint16(data, 5);
        Double pressure = rawPressure == UINT16_INVALID ? null : (rawPressure + 50000.0) / 100.0;

        int powerInfo = readUint16(data, 13);
        Double batteryVoltage = null;
        Integer txPower = null;
        if (powerInfo != UINT16_INVALID) {
            int batteryMilliVolts = powerInfo >> 5;      // top 11 bits
            int txPowerSteps = powerInfo & TX_POWER_INVALID;  // bottom 5
            if (batteryMilliVolts != BATTERY_INVALID) {
                batteryVoltage = (1600.0 + batteryMilliVolts) / 1000.0;
            }
            if (txPowerSteps != TX_POWER_INVALID) {
                txPower = -40 + (txPowerSteps * 2);
            }
        }

        return Optional.of(new RuuviReading(
                Arrays.copyOfRange(data, 18, 24),
                temperature,
                humidity,
                pressure,
                batteryVoltage,
                txPower,
                data[15] & 0xFF,
                readUint16(data, 16),
                rssi,
                at));
    }

    /** Ruuvi's fields are big endian, unlike the rest of BLE. */
    private static int readInt16(byte[] data, int offset) {
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private static int readUint16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
