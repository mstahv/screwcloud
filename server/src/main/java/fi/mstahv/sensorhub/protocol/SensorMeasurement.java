package fi.mstahv.sensorhub.protocol;

/**
 * One sensor's reading. A value is null when the sensor did not provide it — a
 * RuuviTag Pro 2in1, for instance, does not measure humidity at all.
 *
 * @param sensorId at most 4 characters, for example "DHT" or "RBF"
 * @param temperature in degrees Celsius, null if missing
 * @param humidity relative humidity in percent, null if missing
 */
public record SensorMeasurement(String sensorId, Double temperature, Double humidity) {

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
}
