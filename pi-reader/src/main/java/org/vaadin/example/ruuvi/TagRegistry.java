package org.vaadin.example.ruuvi;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The latest reading from every tag heard, kept in memory.
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

    private final Map<String, RuuviReading> byAddress = new ConcurrentHashMap<>();

    /** Keeps the newer of the two, so an advertisement arriving late cannot win. */
    public void store(RuuviReading reading) {
        byAddress.merge(reading.macAddress(), reading,
                (existing, arriving) -> arriving.receivedAt().isBefore(existing.receivedAt())
                        ? existing : arriving);
    }

    /** Every tag heard since startup, in a stable order. */
    public List<RuuviReading> readings() {
        return byAddress.values().stream()
                .sorted(Comparator.comparing(RuuviReading::sensorId)
                        .thenComparing(RuuviReading::macAddress))
                .toList();
    }

    /** The ones heard recently enough to be worth reporting, newest first. */
    public List<RuuviReading> heardWithin(Duration age, Instant now) {
        return readings().stream()
                .filter(reading -> !reading.isOlderThan(age, now))
                .sorted(Comparator.comparing(RuuviReading::receivedAt).reversed())
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
                .collect(java.util.stream.Collectors.groupingBy(RuuviReading::sensorId))
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
