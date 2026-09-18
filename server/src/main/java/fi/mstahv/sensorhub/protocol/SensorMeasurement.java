package fi.mstahv.sensorhub.protocol;

import java.util.Optional;

/**
 * One sensor's reading. A value is null when the sensor did not provide it — a
 * RuuviTag Pro 2in1, for instance, does not measure humidity at all, and only a
 * Ruuvi Air measures the air.
 *
 * <p>Most readings carry two of these four. That is the shape the wire format
 * was changed to allow: in protocol version 1 every sensor record had room for
 * temperature and humidity and nothing else, so a device measuring CO₂ had
 * nowhere to put it. Version 2 sends only the fields a sensor actually has, and
 * nulls here are what "did not send it" looks like on this side.
 *
 * @param sensorId at most 4 characters, for example "DHT" or "RBF"
 * @param temperature in degrees Celsius, null if missing
 * @param humidity relative humidity in percent, null if missing
 * @param co2 carbon dioxide in ppm, null unless the sensor measures air
 * @param pm25 particulates under 2.5 µm, in µg/m³, null unless measured
 * @param batteryVoltage the sensor's own battery in volts, null when it has none
 *        to report — a Ruuvi Air runs off the mains, a DHT22 off the board
 * @param pressure air pressure in hPa, which every Ruuvi measures
 * @param voc Ruuvi's VOC index, 0–500 with 100 as "typical", null unless measured
 * @param nox Ruuvi's NOx index on the same scale, null unless measured
 * @param luminosity light in lux, null unless measured
 */
public record SensorMeasurement(String sensorId, Double temperature, Double humidity,
                                Double co2, Double pm25, Double batteryVoltage,
                                Double pressure, Double voc, Double nox, Double luminosity) {

    /** The fields the format had before the reserved types were put to use. */
    public SensorMeasurement(String sensorId, Double temperature, Double humidity,
                             Double co2, Double pm25, Double batteryVoltage) {
        this(sensorId, temperature, humidity, co2, pm25, batteryVoltage, null, null, null, null);
    }

    /**
     * A reading from a sensor that measures only the two things every sensor
     * has. Most callers and every test that predates the air fields.
     */
    public SensorMeasurement(String sensorId, Double temperature, Double humidity) {
        this(sensorId, temperature, humidity, null, null, null);
    }

    /** The air fields without a battery, which is what a Ruuvi Air reports. */
    public SensorMeasurement(String sensorId, Double temperature, Double humidity,
                             Double co2, Double pm25) {
        this(sensorId, temperature, humidity, co2, pm25, null);
    }

    /**
     * What is left in the battery, guessed from the voltage and this sensor's
     * own temperature — see {@link BatteryEstimate} for how rough a guess, and
     * why the temperature is part of it. Empty for a sensor with no battery.
     */
    public Optional<BatteryEstimate> battery() {
        return BatteryEstimate.of(batteryVoltage, temperature);
    }

    /** Whether the sensor reported a battery, and it is below Ruuvi's replace-it line. */
    public boolean hasLowBattery() {
        return battery().map(BatteryEstimate::low).orElse(false);
    }

    /** Whether this sensor said anything about the air it is standing in. */
    public boolean hasAirQuality() {
        return co2 != null || pm25 != null;
    }

    /**
     * What the firmware calls the microcontroller's own die temperature
     * ({@code INTERNAL_SENSOR_ID}, {@code CPU} unless a device overrides it).
     */
    public static final String INTERNAL_SENSOR_ID = "CPU";

    /**
     * Whether this is the device measuring itself rather than a place somebody
     * chose to measure.
     *
     * <p>It is a diagnostic: it says how warm the board runs and how well the
     * enclosure sheds it, it moves with the load rather than with the weather, and
     * it has no humidity. So it belongs with the device's own status line, not
     * among the measuring points.
     *
     * <p>A device configured with a different {@code INTERNAL_SENSOR_ID} appears as
     * an ordinary sensor instead. That is the honest fallback — the packet carries
     * nothing that would tell the two apart.
     */
    public boolean isDeviceInternal() {
        return INTERNAL_SENSOR_ID.equals(sensorId);
    }

    /**
     * Whether this looks like a RuuviTag, by the identifier every reader derives
     * the same way: {@code R} and hex digits from the tag's address.
     *
     * <p>A guess from a naming convention, which is all the packet offers — it
     * carries no sensor type. It is used only to decide which reading to show first
     * in a summary, so being wrong costs a preview showing one real temperature
     * rather than another.
     */
    public boolean isRuuviTag() {
        if (sensorId == null || sensorId.length() < 2 || sensorId.charAt(0) != 'R') {
            return false;
        }
        return sensorId.chars().skip(1)
                .allMatch(character -> Character.digit(character, 16) >= 0);
    }
}
