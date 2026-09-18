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
 * @param pressure air pressure in hPa, null if missing
 * @param voc Ruuvi's VOC index, null unless measured
 * @param nox Ruuvi's NOx index, null unless measured
 * @param luminosity light in lux, null unless measured
 */
public record HistoryPoint(Instant at, Double temperature, Double humidity,
                           Double co2, Double pm25, Double batteryVoltage,
                           Double pressure, Double voc, Double nox, Double luminosity) {

    /** The fields the format had before the reserved types were put to use. */
    public HistoryPoint(Instant at, Double temperature, Double humidity,
                        Double co2, Double pm25, Double batteryVoltage) {
        this(at, temperature, humidity, co2, pm25, batteryVoltage, null, null, null, null);
    }

    /** The two values every sensor has, which is all most callers and tests need. */
    public HistoryPoint(Instant at, Double temperature, Double humidity) {
        this(at, temperature, humidity, null, null, null);
    }
}
