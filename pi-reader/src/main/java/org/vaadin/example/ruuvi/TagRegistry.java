package org.vaadin.example.ruuvi;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.vaadin.example.sensor.Reading;

/**
 * The latest reading from every sensor heard, kept in memory.
 *
 * <p>Holds whatever implements {@link Reading}: RuuviTags heard over the air and
 * the Thingy:52 this reader connects to, in one place, because everything
 * downstream wants them in one place and none of it cares which is which.
 *
 * <p>Keyed by address rather than by sensor identifier. The identifier is derived
 * from twelve bits of the address, so two tags can in principle share one, and
 * keying by it would silently drop a real tag from the display. Here they are both
 * kept and {@link #duplicateSensorIds()} names the clash.
 *
 * <p>Nothing is ever evicted. Tags are counted in single digits, a reading is a
 * few dozen bytes, and forgetting one that has gone quiet would remove the very
 * thing a reader is looking for when they walk over to check.
 *
 * <p>Written from the scanner thread and read from HTTP threads, so the map is
 * concurrent and the readings are immutable records.
 */
@ApplicationScoped
public class TagRegistry {

    private final Map<String, Reading> byAddress = new ConcurrentHashMap<>();

    /** Keeps the newer of the two, so an advertisement arriving late cannot win. */
    public void store(Reading reading) {
        byAddress.merge(reading.macAddress(), reading,
                (existing, arriving) -> arriving.receivedAt().isBefore(existing.receivedAt())
                        ? existing : arriving);
    }

    /** Every sensor heard since startup, in a stable order. */
    public List<Reading> readings() {
        return byAddress.values().stream()
                .sorted(Comparator.comparing(Reading::sensorId)
                        .thenComparing(Reading::macAddress))
                .toList();
    }

    /** The ones heard recently enough to be worth reporting, newest first. */
    public List<Reading> heardWithin(Duration age, Instant now) {
        return readings().stream()
                .filter(reading -> !reading.isOlderThan(age, now))
                .sorted(Comparator.comparing(Reading::receivedAt).reversed())
                .toList();
    }

    /**
     * Identifiers that more than one tag would report under. Empty in every
     * ordinary case; when it is not, two measuring points would arrive at the
     * server as one, and only a rename in the firmware's terms could tell them
     * apart.
     */
    public List<String> duplicateSensorIds() {
        return readings().stream()
                .collect(java.util.stream.Collectors.groupingBy(Reading::sensorId))
                .entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    public int size() {
        return byAddress.size();
    }
}
