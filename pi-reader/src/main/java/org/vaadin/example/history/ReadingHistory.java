package org.vaadin.example.history;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.vaadin.example.ruuvi.RuuviReading;

/**
 * The last day of readings, in memory.
 *
 * <p>In memory and nowhere else, on purpose. A curve of the last day is what makes
 * a temperature mean something — 4 °C on the way up is a different story from 4 °C
 * on the way down — but it is not worth a database on a Pi, and losing it on a
 * restart costs a day of a picture the server keeps anyway.
 *
 * <p>Sampled rather than recorded. A tag broadcasts every 1.3 seconds, which over a
 * day is sixty thousand points per tag to draw a curve four hundred pixels wide.
 * One point a minute is finer than the curve can show and finer than the server
 * stores, at a thousandth of the memory.
 *
 * <p>Written from the scanner thread and read from HTTP threads: the map is
 * concurrent, each tag's deque is guarded by itself, and what leaves is a copy.
 */
@ApplicationScoped
public class ReadingHistory {

    /** How far back the curve goes. */
    public static final Duration WINDOW = Duration.ofHours(24);

    /** How close together two points may be. */
    static final Duration SAMPLE_INTERVAL = Duration.ofMinutes(1);

    private final Map<String, Deque<HistoryPoint>> byAddress = new ConcurrentHashMap<>();

    /**
     * Records a reading, unless one was taken for this tag less than
     * {@link #SAMPLE_INTERVAL} ago.
     */
    public void add(RuuviReading reading) {
        Deque<HistoryPoint> points =
                byAddress.computeIfAbsent(reading.macAddress(), key -> new ArrayDeque<>());
        synchronized (points) {
            HistoryPoint last = points.peekLast();
            if (last != null
                    && Duration.between(last.at(), reading.receivedAt()).compareTo(SAMPLE_INTERVAL) < 0) {
                return;
            }
            points.addLast(new HistoryPoint(
                    reading.receivedAt(), reading.temperature(), reading.humidity()));
            prune(points, reading.receivedAt());
        }
    }

    /** This tag's points, oldest first, which is the order a curve is drawn in. */
    public List<HistoryPoint> pointsFor(String macAddress) {
        Deque<HistoryPoint> points = byAddress.get(macAddress);
        if (points == null) {
            return List.of();
        }
        synchronized (points) {
            return List.copyOf(points);
        }
    }

    /**
     * Drops what has fallen out of the window. From the front only: the points go in
     * oldest first, so the first one still inside the window ends it.
     */
    private static void prune(Deque<HistoryPoint> points, Instant now) {
        Instant oldest = now.minus(WINDOW);
        while (!points.isEmpty() && points.peekFirst().at().isBefore(oldest)) {
            points.removeFirst();
        }
    }
}
