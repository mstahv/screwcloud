package fi.mstahv.sensorhub.store;

import java.util.Optional;

/**
 * Temperature bands for one sensor, from cold to hot:
 *
 * <pre>
 *   alert   warning        OK        warning   alert
 * ────────┬──────────┬─────────────┬──────────┬────────
 *      alertLow    okLow         okHigh   alertHigh
 * </pre>
 *
 * <p>All four values are null when the sensor has no bands configured, in which
 * case the gauge keeps its stock range and colours. Partially filled bands are
 * rejected when saving, so a set is either complete or empty.
 *
 * @param alertLow below this it is an alert
 * @param okLow start of the OK band
 * @param okHigh end of the OK band
 * @param alertHigh above this it is an alert
 */
public record SensorThresholds(Double alertLow, Double okLow, Double okHigh, Double alertHigh) {

    public static final SensorThresholds NONE = new SensorThresholds(null, null, null, null);

    public boolean isConfigured() {
        return alertLow != null && okLow != null && okHigh != null && alertHigh != null;
    }

    /**
     * Which band a reading falls in, or empty when the sensor has no bands or no
     * reading — there is nothing to judge it against.
     *
     * <p>The limits are inclusive towards OK: exactly {@code okLow} is OK rather
     * than a warning. A limit is where a band starts, and a reading sitting on it
     * should not raise an alarm the reader would have to look up the definition
     * to understand.
     */
    public Optional<TemperatureZone> zoneOf(Double temperature) {
        if (temperature == null || !isConfigured()) {
            return Optional.empty();
        }
        if (temperature < alertLow) {
            return Optional.of(TemperatureZone.ALERT_LOW);
        }
        if (temperature < okLow) {
            return Optional.of(TemperatureZone.WARNING_LOW);
        }
        if (temperature <= okHigh) {
            return Optional.of(TemperatureZone.OK);
        }
        if (temperature <= alertHigh) {
            return Optional.of(TemperatureZone.WARNING_HIGH);
        }
        return Optional.of(TemperatureZone.ALERT_HIGH);
    }

    /**
     * @throws IllegalArgumentException if the values are partially filled or out
     *         of order
     */
    public void validate() {
        int given = 0;
        for (Double value : new Double[] {alertLow, okLow, okHigh, alertHigh}) {
            if (value != null) {
                given++;
            }
        }
        if (given == 0) {
            return;
        }
        if (given < 4) {
            throw new IllegalArgumentException(
                    "Give all four temperature limits, or leave them all empty");
        }
        /*
           Strictly increasing rather than merely non-decreasing: an empty band
           would render as a zero-width arc, which looks like a rendering bug
           rather than a configuration choice.
        */
        if (!(alertLow < okLow && okLow < okHigh && okHigh < alertHigh)) {
            throw new IllegalArgumentException(
                    "The limits must increase: alert low < OK low < OK high < alert high");
        }
    }
}
