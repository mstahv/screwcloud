package fi.mstahv.sensorhub.store;

import java.time.Instant;

/**
 * One point in a time series. Its own type so the UI does not handle JPA
 * entities directly.
 *
 * @param at when the measurement was received
 * @param temperature in degrees Celsius, null if the sensor provided no value
 * @param humidity relative humidity in percent, null if missing
 */
public record HistoryPoint(Instant at, Double temperature, Double humidity) {
}
