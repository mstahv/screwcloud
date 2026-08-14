package org.vaadin.example.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.vaadin.example.ruuvi.RuuviReading;

class ReadingHistoryTest {

    private static final Instant START = Instant.parse("2026-08-14T09:00:00Z");
    private static final String MAC = "CB:B8:33:4C:88:4F";

    private final ReadingHistory history = new ReadingHistory();

    /**
     * A tag broadcasts every 1.3 seconds. Recording every one of them would be
     * sixty thousand points a day to draw a curve four hundred pixels wide.
     */
    @Test
    void broadcastsWithinTheSampleIntervalAreNotAllRecorded() {
        for (int second = 0; second < 120; second++) {
            history.add(reading(20.0, START.plusSeconds(second)));
        }

        // One at the start and one a minute later: two minutes hold two samples.
        assertEquals(2, history.pointsFor(MAC).size());
    }

    @Test
    void aPointIsKeptOnceTheIntervalHasPassed() {
        history.add(reading(20.0, START));
        history.add(reading(21.0, START.plus(Duration.ofMinutes(1))));

        assertEquals(List.of(20.0, 21.0),
                history.pointsFor(MAC).stream().map(HistoryPoint::temperature).toList());
    }

    @Test
    void thePointsComeOldestFirstWhichIsHowACurveIsDrawn() {
        for (int minute = 0; minute < 5; minute++) {
            history.add(reading(20.0 + minute, START.plus(Duration.ofMinutes(minute))));
        }

        List<HistoryPoint> points = history.pointsFor(MAC);
        assertEquals(20.0, points.getFirst().temperature());
        assertEquals(24.0, points.getLast().temperature());
    }

    /** A day, and no more: this is memory on a Pi, not a database. */
    @Test
    void anythingOlderThanTheWindowIsDropped() {
        history.add(reading(20.0, START));
        history.add(reading(21.0, START.plus(ReadingHistory.WINDOW).plusSeconds(1)));

        List<HistoryPoint> points = history.pointsFor(MAC);
        assertEquals(1, points.size());
        assertEquals(21.0, points.getFirst().temperature());
    }

    /** The oldest point still inside the window survives the pruning. */
    @Test
    void whatIsStillInsideTheWindowIsKept() {
        history.add(reading(20.0, START));
        history.add(reading(21.0, START.plus(Duration.ofHours(12))));
        history.add(reading(22.0, START.plus(Duration.ofHours(23))));

        assertEquals(3, history.pointsFor(MAC).size());
    }

    @Test
    void eachTagHasItsOwnCurve() {
        history.add(reading(MAC, 20.0, START));
        history.add(reading("CB:B8:33:4C:88:5F", 5.0, START));

        assertEquals(1, history.pointsFor(MAC).size());
        assertEquals(5.0, history.pointsFor("CB:B8:33:4C:88:5F").getFirst().temperature());
    }

    @Test
    void aTagNeverHeardHasNoCurveRatherThanNoAnswer() {
        assertTrue(history.pointsFor("00:00:00:00:00:00").isEmpty());
    }

    /**
     * A tag with no humidity still gets its temperature curve; the missing value
     * is recorded as missing rather than as zero.
     */
    @Test
    void aMissingValueIsRecordedAsMissing() {
        history.add(new RuuviReading(mac(MAC), 20.0, null, null, null, null, 0, 1, null, START));

        assertEquals(null, history.pointsFor(MAC).getFirst().humidity());
        assertEquals(20.0, history.pointsFor(MAC).getFirst().temperature());
    }

    private static RuuviReading reading(double temperature, Instant at) {
        return reading(MAC, temperature, at);
    }

    private static RuuviReading reading(String mac, double temperature, Instant at) {
        return new RuuviReading(mac(mac), temperature, 45.0, 1000.0, 3.0, 4, 0, 1, (short) -60, at);
    }

    private static byte[] mac(String mac) {
        String[] parts = mac.split(":");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }
}
