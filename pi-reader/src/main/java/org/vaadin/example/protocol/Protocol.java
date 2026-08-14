package org.vaadin.example.protocol;

/**
 * The measurement packet format, as the microcontroller firmwares define it in
 * {@code Protocol.h}.
 *
 * <p>This is a third implementation of the same wire format — the Pico sketch, the
 * ESP32 sketch and this reader all speak it, and the server decodes it. The
 * constants are therefore not free to drift: {@code ProtocolSyncTest} reads
 * {@code Protocol.h} and fails if they do.
 *
 * <p>Everything is big endian, and both identifiers are fixed width and padded
 * with spaces rather than terminated.
 *
 * <pre>
 * header, 8 bytes
 *   0      version
 *   1..4   device id
 *   5      sensor count
 *   6..7   sequence number
 * then 8 bytes per sensor
 *   0..3   sensor id
 *   4..5   temperature, hundredths of a degree, signed
 *   6..7   humidity, hundredths of a percent, unsigned
 * </pre>
 */
public final class Protocol {

    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 8;
    public static final int SENSOR_SIZE = 8;
    public static final int ID_SIZE = 4;
    public static final int MAX_SENSORS = 8;

    /** What a sensor that has no reading sends instead of a temperature. */
    public static final short TEMPERATURE_INVALID = (short) 0x8000;

    /** The same for humidity, which a RuuviTag Pro 2in1 never reports. */
    public static final int HUMIDITY_INVALID = 0xFFFF;

    /*
       The bounds the firmware checks before scaling. Outside them the value would
       not survive the round trip, so it is sent as "missing" rather than as a
       number that is merely wrong.
    */
    private static final double TEMPERATURE_LIMIT = 327.0;
    private static final double HUMIDITY_LIMIT = 655.0;

    private Protocol() {
    }

    /** Degrees Celsius to hundredths, or the sentinel if it will not fit. */
    public static short encodeTemperature(Double celsius) {
        if (celsius == null || celsius.isNaN()
                || celsius < -TEMPERATURE_LIMIT || celsius > TEMPERATURE_LIMIT) {
            return TEMPERATURE_INVALID;
        }
        return (short) round(celsius * 100.0);
    }

    /** Relative humidity in percent to hundredths, or the sentinel. */
    public static int encodeHumidity(Double percent) {
        if (percent == null || percent.isNaN() || percent < 0.0 || percent > HUMIDITY_LIMIT) {
            return HUMIDITY_INVALID;
        }
        return (int) round(percent * 100.0) & 0xFFFF;
    }

    /**
     * Half away from zero, which is what the firmware's {@code lroundf} does.
     *
     * <p>Not {@link Math#round}: that rounds a half towards positive infinity, so
     * -12.345 °C would be sent as -12.34 here and as -12.35 by a microcontroller
     * standing next to it. A hundredth of a degree matters to nobody, but two
     * devices reporting the same reading differently is the kind of difference that
     * gets chased for an afternoon.
     */
    private static long round(double value) {
        return value < 0 ? -Math.round(-value) : Math.round(value);
    }
}
