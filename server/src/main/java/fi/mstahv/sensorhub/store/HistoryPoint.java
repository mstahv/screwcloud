package fi.mstahv.sensorhub.store;

import java.time.Instant;

/**
 * One point in a time series. Its own type so the UI does not handle JPA
 * entities directly.
 *
 * <p>Every value the row holds, not only the temperature the curve is drawn
 * from: the card can lay any of the others over it on request, and the
 * degree-day arithmetic reads the temperature and nothing else.
 *
 * @param at when the measurement was received
 * @param temperature in degrees Celsius, null if the sensor provided no value
 * @param humidity relative humidity in percent, null if missing
 * @param co2 carbon dioxide in ppm, null unless the sensor measures air
 * @param pm25 particulates under 2.5 µm in µg/m³, null unless measured
 * @param batteryVoltage the sensor's own battery in volts, null if it has none
 */
public record HistoryPoint(Instant at, Double temperature, Double humidity,
                           Double co2, Double pm25, Double batteryVoltage) {

    /** The two values every sensor has, which is all most callers and tests need. */
    public HistoryPoint(Instant at, Double temperature, Double humidity) {
        this(at, temperature, humidity, null, null, null);
    }
}
