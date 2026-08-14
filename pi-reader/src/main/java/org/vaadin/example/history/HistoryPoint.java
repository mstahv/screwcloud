package org.vaadin.example.history;

import java.time.Instant;

/**
 * One point in a time series.
 *
 * @param at when the reading was heard
 * @param temperature degrees Celsius, null if the tag reported none
 * @param humidity relative humidity in percent, null if missing
 */
public record HistoryPoint(Instant at, Double temperature, Double humidity) {
}
