package fi.mstahv.sensorhub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import fi.mstahv.sensorhub.store.HistoryPoint;

/**
 * The arithmetic behind the hanging counters, checked against the examples in the
 * practice it comes from.
 */
class HeatSumTest {

    private static final Instant START = Instant.parse("2026-08-10T12:00:00Z");

    /*
       The guideline's own example: 40 degree-days is +8 °C for five days, or +5 °C
       for eight. If this does not hold, nothing else about the feature matters.
    */
    @Test
    void theGuidelineExamplesComeOut() {
        assertEquals(40.0, HeatSum.of(steady(8.0, Duration.ofDays(5))).degreeDays(), 0.05);
        assertEquals(40.0, HeatSum.of(steady(5.0, Duration.ofDays(8))).degreeDays(), 0.05);
    }

    @Test
    void degreeDaysAreTemperatureTimesTime() {
        assertEquals(10.0, HeatSum.of(steady(10.0, Duration.ofDays(1))).degreeDays(), 0.05);
        assertEquals(5.0, HeatSum.of(steady(10.0, Duration.ofHours(12))).degreeDays(), 0.05);
    }

    /*
       "Jäätynyt liha ei mureudu" — frozen meat does not tenderise. Time below zero
       contributes nothing.
    */
    @Test
    void timeBelowFreezingAddsNothing() {
        assertEquals(0.0, HeatSum.of(steady(-5.0, Duration.ofDays(3))).degreeDays(), 0.001);
    }

    /*
       And it does not subtract either: what has already tenderised does not
       un-tenderise in the cold.
    */
    @Test
    void frostDoesNotUndoWhatWasAccumulated() {
        List<HistoryPoint> history = new ArrayList<>(steady(6.0, Duration.ofDays(2)));
        history.addAll(continuing(history, -10.0, Duration.ofDays(2)));

        assertEquals(12.0, HeatSum.of(history).degreeDays(), 0.1);
    }

    /*
       A counter started a moment ago has one reading and no elapsed time, so
       nothing has accumulated — but the temperature right now is a perfectly good
       estimate of the rate, and without it the card has nothing to say but
       "waiting".
    */
    @Test
    void aSingleReadingAccumulatesNothingButStillGivesARate() {
        HeatSum sum = HeatSum.of(List.of(new HistoryPoint(START, 23.0, 40.0)));

        assertEquals(0.0, sum.degreeDays());
        assertEquals(23.0, sum.recentRate().orElseThrow(), 0.001);
    }

    /*
       The bug this was found by: a counter started in a warm room reported "not
       accumulating — below freezing", because a missing rate was being read as
       frozen.
    */
    @Test
    void aFreshCounterInARoomForecastsFromTheCurrentTemperature() {
        HeatSum sum = HeatSum.of(List.of(new HistoryPoint(START, 23.0, 40.0)));

        Duration remaining = sum.remaining(40).orElseThrow();

        // 40 degree-days at 23 °C is a day and 18 hours.
        assertEquals(42, remaining.toHours(), 1);
        assertTrue(sum.forecast(40, START).isPresent());
    }

    @Test
    void aSingleReadingBelowFreezingIsStillNotAccumulating() {
        HeatSum sum = HeatSum.of(List.of(new HistoryPoint(START, -4.0, 40.0)));

        assertEquals(0.0, sum.recentRate().orElseThrow(), 0.001);
        assertTrue(sum.remaining(40).isEmpty());
    }

    /*
       No readings at all is a different thing from a frozen shed, and the two have
       to stay distinguishable — the UI says something different about each.
    */
    @Test
    void noReadingsMeansNoRateAtAll() {
        HeatSum sum = HeatSum.of(List.of());

        assertTrue(sum.recentRate().isEmpty());
        assertEquals(0.0, sum.degreeDays());
    }

    @Test
    void aReadingWithoutATemperatureIsNotAReading() {
        HeatSum sum = HeatSum.of(List.of(new HistoryPoint(START, null, 40.0)));

        assertTrue(sum.recentRate().isEmpty());
    }

    @Test
    void readingsWithoutATemperatureAreIgnored() {
        List<HistoryPoint> history = List.of(
                new HistoryPoint(START, 6.0, null),
                new HistoryPoint(START.plus(Duration.ofDays(1)), null, null),
                new HistoryPoint(START.plus(Duration.ofDays(2)), 6.0, null));

        // Two usable readings two days apart at 6 °C.
        assertEquals(12.0, HeatSum.of(history).degreeDays(), 0.1);
    }

    /*
       A gap is interpolated rather than dropped. Skipping it would under-count, and
       under-counting means hanging longer than the meat needs.
    */
    @Test
    void aGapInTheDataIsInterpolated() {
        List<HistoryPoint> history = List.of(
                new HistoryPoint(START, 4.0, null),
                new HistoryPoint(START.plus(Duration.ofHours(12)), 4.0, null),
                // six hours missing here
                new HistoryPoint(START.plus(Duration.ofHours(18)), 4.0, null),
                new HistoryPoint(START.plus(Duration.ofDays(1)), 4.0, null));

        assertEquals(4.0, HeatSum.of(history).degreeDays(), 0.05);
    }

    @Test
    void theRateIsTheRecentAverageTemperature() {
        HeatSum sum = HeatSum.of(steady(6.0, Duration.ofDays(2)));

        assertEquals(6.0, sum.recentRate().orElseThrow(), 0.1);
    }

    /*
       The rate follows the recent past, not the whole history: a counter that has
       been running warm for days should forecast from today's cold snap.
    */
    @Test
    void theRateFollowsTheRecentPastNotTheWholeHistory() {
        List<HistoryPoint> history = new ArrayList<>(steady(10.0, Duration.ofDays(3)));
        history.addAll(continuing(history, 2.0, Duration.ofHours(8)));

        assertEquals(2.0, HeatSum.of(history).recentRate().orElseThrow(), 0.3);
    }

    @Test
    void theForecastIsTheRemainingSumAtTheCurrentRate() {
        // 12 degree-days accumulated at 6 °C per day; 28 left of 40 is 4 days and 16 h.
        HeatSum sum = HeatSum.of(steady(6.0, Duration.ofDays(2)));

        Duration remaining = sum.remaining(40).orElseThrow();

        assertEquals(4, remaining.toDays());
        assertEquals(112, remaining.toHours(), 2);
    }

    @Test
    void aReachedTargetHasNothingLeft() {
        HeatSum sum = HeatSum.of(steady(8.0, Duration.ofDays(5)));

        assertTrue(sum.reached(40));
        assertEquals(Duration.ZERO, sum.remaining(40).orElseThrow());
        assertEquals(START, sum.forecast(40, START).orElseThrow());
    }

    /*
       A frozen shed has no completion date, and saying "in 4000 days" would be
       worse than saying nothing.
    */
    @Test
    void thereIsNoForecastWhileNothingAccumulates() {
        HeatSum sum = HeatSum.of(steady(-3.0, Duration.ofDays(2)));

        assertFalse(sum.reached(40));
        assertTrue(sum.remaining(40).isEmpty());
        assertTrue(sum.forecast(40, START).isEmpty());
    }

    @Test
    void theMeasuredSpanIsReported() {
        assertEquals(Duration.ofDays(2), HeatSum.of(steady(5.0, Duration.ofDays(2))).measuredOver());
    }

    /** Readings every 15 minutes at a steady temperature, oldest first. */
    private static List<HistoryPoint> steady(double temperature, Duration over) {
        List<HistoryPoint> history = new ArrayList<>();
        for (Duration at = Duration.ZERO; at.compareTo(over) <= 0; at = at.plusMinutes(15)) {
            history.add(new HistoryPoint(START.plus(at), temperature, 40.0));
        }
        return history;
    }

    /** More readings after the given history, at a different temperature. */
    private static List<HistoryPoint> continuing(List<HistoryPoint> history, double temperature,
                                                 Duration over) {
        Instant from = history.getLast().at();
        List<HistoryPoint> more = new ArrayList<>();
        for (Duration at = Duration.ofMinutes(15); at.compareTo(over) <= 0; at = at.plusMinutes(15)) {
            more.add(new HistoryPoint(from.plus(at), temperature, 40.0));
        }
        return more;
    }
}
