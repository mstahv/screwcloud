package org.vaadin.example.ruuvi;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Ruuvi's data format 6, the legacy-size advertisement of a Ruuvi Air.
 *
 * <p>The Air broadcasts two formats: this one, which fits an ordinary BLE
 * advertisement and any scanner, and the extended-advertising format E1 with
 * full precision and every particulate size. This reader decodes the one it is
 * guaranteed to hear; what E1 would add is finer PM classes and the full
 * address, neither of which the page needs yet.
 *
 * <p>The layout and scalings are taken from Ruuvi's own
 * <a href="https://github.com/ruuvi/ruuvi.endpoints.c">ruuvi.endpoints.c</a>
 * ({@code ruuvi_endpoint_6}), the same code the device firmware encodes with:
 * 20 bytes after the company id — header, five 16-bit fields (temperature,
 * humidity, pressure, PM2.5, CO₂), three 9-bit fields whose ninth bits live in
 * the flags byte (VOC, NOx, average sound), a logarithmic luminosity byte, a
 * sequence byte, flags, and the low three bytes of the address.
 *
 * <p>Every field has its own value meaning "not available", and each one
 * becomes null rather than a plausible-looking number — the air sensors send
 * exactly those markers while warming up.
 */
public final class DataFormat6 {

    static final int FORMAT = 0x06;

    /** Bytes after the company id. */
    static final int LENGTH = 20;

    private static final int TEMPERATURE_INVALID = (short) 0x8000;
    private static final int UINT16_INVALID = 0xFFFF;
    private static final int UINT9_INVALID = 0x1FF;
    private static final int LUMINOSITY_INVALID = 0xFF;

    /** The firmware's log encoding: 254 steps across ln(65536). */
    private static final double LUMINOSITY_RATIO = 254.0 / Math.log(65536.0);

    private static final double SOUND_MIN_DBA = 18.0;
    private static final double SOUND_RATIO = 5.0;

    private static final int FLAG_CALIBRATION_IN_PROGRESS = 1;
    private static final int SOUND_BIT9 = 4;
    private static final int VOC_BIT9 = 6;
    private static final int NOX_BIT9 = 7;

    private DataFormat6() {
    }

    /**
     * @param data the manufacturer-specific payload, starting at the format byte
     * @param at   when it was heard
     * @param rssi signal strength, or null if the receiver did not report one
     * @return the reading, or empty if this is not a data format 6 advertisement
     */
    public static Optional<AirReading> parse(byte[] data, Instant at, Short rssi) {
        if (data == null || data.length < LENGTH || (data[0] & 0xFF) != FORMAT) {
            return Optional.empty();
        }

        int flags = data[16] & 0xFF;

        int rawTemperature = readInt16(data, 1);
        Double temperature = rawTemperature == TEMPERATURE_INVALID
                ? null : rawTemperature / 200.0;

        Double humidity = decodeUint16(data, 3, 400.0, 0.0);
        Double pressure = decodeUint16(data, 5, 1.0, 50000.0);
        if (pressure != null) {
            pressure = pressure / 100.0;   // the wire is pascals, the page reads hPa
        }
        Double pm25 = decodeUint16(data, 7, 10.0, 0.0);
        Double co2 = decodeUint16(data, 9, 1.0, 0.0);

        Double voc = decodeUint9(data, 11, flags, VOC_BIT9, 1.0, 0.0);
        Double nox = decodeUint9(data, 12, flags, NOX_BIT9, 1.0, 0.0);

        int rawLuminosity = data[13] & 0xFF;
        Double luminosity = rawLuminosity == LUMINOSITY_INVALID
                ? null : Math.exp(rawLuminosity / LUMINOSITY_RATIO) - 1.0;

        Double soundAvg = decodeUint9(data, 14, flags, SOUND_BIT9, SOUND_RATIO, SOUND_MIN_DBA);

        return Optional.of(new AirReading(
                Arrays.copyOfRange(data, 17, 20),
                temperature, humidity, pressure,
                pm25, co2, voc, nox, luminosity, soundAvg,
                (flags & FLAG_CALIBRATION_IN_PROGRESS) != 0,
                data[15] & 0xFF,
                rssi, at));
    }

    /** value = raw / ratio + min, with 0xFFFF meaning "not available". */
    private static Double decodeUint16(byte[] data, int offset, double ratio, double min) {
        int raw = readUint16(data, offset);
        return raw == UINT16_INVALID ? null : raw / ratio + min;
    }

    /**
     * A 9-bit value: eight bits in its own byte, the ninth — the <em>lowest</em>
     * bit, not the highest — parked in the flags byte. value = raw / ratio + min.
     */
    private static Double decodeUint9(byte[] data, int offset, int flags, int bit,
                                      double ratio, double min) {
        int raw = ((data[offset] & 0xFF) << 1) | ((flags >> bit) & 1);
        return raw == UINT9_INVALID ? null : raw / ratio + min;
    }

    private static int readInt16(byte[] data, int offset) {
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private static int readUint16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
