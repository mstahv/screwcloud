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
 * <p>Everything is big endian, and identifiers are fixed width and padded with
 * spaces rather than terminated.
 *
 * <pre>
 * header, 8 bytes
 *   0      version = 2
 *   1..4   device id
 *   5      sensor count
 *   6..7   sequence number
 * then per sensor
 *   0..3   sensor id
 *   4      field count
 *   then 3 bytes per field
 *     0    type, from the registry below
 *     1..2 value
 * </pre>
 *
 * <p>Version 1 was a fixed 8-byte sensor record holding a temperature and a
 * humidity, with sentinel values standing in for a reading the sensor did not
 * have. It had no room for a third measurement, which is what the Ruuvi Air
 * wanted — {@code protocol-evolution.md} worked through the alternatives and
 * recommended this one. Two consequences worth knowing:
 *
 * <ul>
 * <li><b>A missing value is an absent field.</b> The sentinels are gone, because
 * a format that can leave a field out does not need them.
 * <li><b>An unknown type is skipped.</b> Every field is the same three bytes, so
 * a reader can step over what it does not recognise and keep the rest.
 * </ul>
 *
 * <p>This reader only encodes, so it needs the numbers and the scalings and none
 * of the decoding. The server has that half.
 */
public final class Protocol {

    public static final int VERSION = 2;
    public static final int HEADER_SIZE = 8;
    public static final int ID_SIZE = 4;
    public static final int MAX_SENSORS = 8;

    /** A sensor record before its fields: the identifier and the field count. */
    public static final int SENSOR_HEADER_SIZE = ID_SIZE + 1;

    /** One field: a type byte and a 16-bit value. */
    public static final int FIELD_SIZE = 3;
    /** As many as there are types; a Ruuvi Air sends eight. */
    public static final int MAX_FIELDS = 9;

    /*
       The field type registry. Numbers are permanent: a type means the same thing
       and carries the same scaling forever, in every firmware and every reader.
       Adding a measurement takes the next free number, never a reused one.
    */
    public static final int FIELD_TEMPERATURE = 1;  // int16,  0.01 °C
    public static final int FIELD_HUMIDITY = 2;     // uint16, 0.01 %RH
    public static final int FIELD_PRESSURE = 3;     // uint16, 0.1 hPa
    public static final int FIELD_CO2 = 4;          // uint16, ppm
    public static final int FIELD_PM25 = 5;         // uint16, 0.1 µg/m³
    public static final int FIELD_VOC = 6;          // uint16, Ruuvi's index, 0–500
    public static final int FIELD_NOX = 7;          // uint16, Ruuvi's index, 0–500
    public static final int FIELD_BATTERY = 8;      // uint16, mV — the sensor's own battery
    public static final int FIELD_LUMINOSITY = 9;   // uint16, lx

    /*
       The bounds the firmware checks before scaling. Outside them the value would
       not survive the round trip, so the field is left out rather than sent as a
       number that is merely wrong.
    */
    private static final double TEMPERATURE_LIMIT = 327.0;
    private static final double HUMIDITY_LIMIT = 655.0;
    private static final double CO2_LIMIT = 65535.0;
    private static final double PM25_LIMIT = 6553.0;
    private static final double BATTERY_LIMIT = 65.535;
    private static final double PRESSURE_LIMIT = 6553.5;
    private static final double INDEX_LIMIT = 65535.0;
    private static final double LUMINOSITY_LIMIT = 65535.0;

    private Protocol() {
    }

    /**
     * One field of a sensor record, or nothing.
     *
     * <p>The "or nothing" is the whole difference from version 1. There, a value
     * the sensor did not have still occupied its four bytes and said so with a
     * sentinel; here it simply does not appear, and the encoder's job is to
     * decide which fields exist rather than what to put in the empty ones.
     *
     * @param type from the registry above
     * @param value already scaled, and masked to the 16 bits the field holds
     */
    public record Field(int type, int value) {
    }

    /** Degrees Celsius as hundredths, or null if the field should not be sent. */
    public static Field temperature(Double celsius) {
        if (celsius == null || celsius.isNaN()
                || celsius < -TEMPERATURE_LIMIT || celsius > TEMPERATURE_LIMIT) {
            return null;
        }
        return new Field(FIELD_TEMPERATURE, (int) round(celsius * 100.0) & 0xFFFF);
    }

    /** Relative humidity in percent as hundredths. */
    public static Field humidity(Double percent) {
        return unsigned(FIELD_HUMIDITY, percent, 100.0, HUMIDITY_LIMIT);
    }

    /** Carbon dioxide in ppm, whole numbers — the sensor's accuracy is tens. */
    public static Field co2(Double ppm) {
        return unsigned(FIELD_CO2, ppm, 1.0, CO2_LIMIT);
    }

    /** Particulates under 2.5 µm, in tenths of a µg/m³. */
    public static Field pm25(Double microgramsPerCubicMetre) {
        return unsigned(FIELD_PM25, microgramsPerCubicMetre, 10.0, PM25_LIMIT);
    }

    /** The sensor's battery in millivolts; a coin cell is a number near 3000. */
    public static Field battery(Double volts) {
        return unsigned(FIELD_BATTERY, volts, 1000.0, BATTERY_LIMIT);
    }

    /** Air pressure in tenths of a hectopascal. */
    public static Field pressure(Double hectopascals) {
        return unsigned(FIELD_PRESSURE, hectopascals, 10.0, PRESSURE_LIMIT);
    }

    /** Ruuvi's VOC index, a whole number. */
    public static Field voc(Double index) {
        return unsigned(FIELD_VOC, index, 1.0, INDEX_LIMIT);
    }

    /** Ruuvi's NOx index, a whole number. */
    public static Field nox(Double index) {
        return unsigned(FIELD_NOX, index, 1.0, INDEX_LIMIT);
    }

    /** Light in whole lux. */
    public static Field luminosity(Double lux) {
        return unsigned(FIELD_LUMINOSITY, lux, 1.0, LUMINOSITY_LIMIT);
    }

    private static Field unsigned(int type, Double value, double scale, double limit) {
        if (value == null || value.isNaN() || value < 0.0 || value > limit) {
            return null;
        }
        return new Field(type, (int) round(value * scale) & 0xFFFF);
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
