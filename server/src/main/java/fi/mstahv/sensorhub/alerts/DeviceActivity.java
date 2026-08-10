package fi.mstahv.sensorhub.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a device is still reporting, judged against its own rhythm.
 *
 * <p>Nothing tells the server how often a device sends: the interval lives in the
 * firmware's config.h and can be anything. So it is learned from the arrivals
 * instead — the <b>median</b> gap between recent packets, which ignores the
 * occasional missed send that would drag a mean upwards.
 *
 * <p>The same value decides both the notification and the badge in the UI, so the
 * two cannot disagree about what "offline" means.
 *
 * @param expectedInterval the learned rhythm, or empty when there is not enough
 *        history to know it
 * @param sinceLast how long since the last packet
 * @param silent whether that is long enough to count as gone
 */
public record DeviceActivity(Optional<Duration> expectedInterval, Duration sinceLast, boolean silent) {

    /**
     * How many reports have to be missed before a device counts as silent. Three
     * because one missed send is normal — a lost UDP packet, a modem retry — and
     * two could still be a bad few minutes. Three in a row means something is
     * actually wrong.
     */
    static final int MISSED_REPORTS = 3;

    /**
     * Half an interval of slack on top, so a device whose timing drifts slightly
     * is not declared dead the moment the third report is theoretically due.
     */
    private static final double GRACE = 0.5;

    /**
     * At least this many arrivals are needed before any judgement: two arrivals
     * give one gap, which could be anything. Four give three gaps and a median
     * worth trusting.
     */
    static final int MINIMUM_ARRIVALS = 4;

    /** Nothing has ever been heard from this device. */
    public static DeviceActivity neverHeard() {
        return new DeviceActivity(Optional.empty(), Duration.ZERO, false);
    }

    /**
     * @param arrivals when recent packets arrived, newest first
     * @param now the moment to judge against
     */
    public static DeviceActivity of(List<Instant> arrivals, Instant now) {
        if (arrivals.isEmpty()) {
            return neverHeard();
        }
        Duration sinceLast = Duration.between(arrivals.getFirst(), now);
        Optional<Duration> interval = medianGap(arrivals);
        /*
           Without a known rhythm nothing is declared silent. A device that has
           just been set up should not be reported as offline before it has
           established how often it reports.
        */
        boolean silent = interval
                .map(expected -> sinceLast.compareTo(threshold(expected)) > 0)
                .orElse(false);
        return new DeviceActivity(interval, sinceLast, silent);
    }

    /** How long a device may stay quiet before it counts as gone. */
    public static Duration threshold(Duration expectedInterval) {
        return Duration.ofMillis(
                Math.round(expectedInterval.toMillis() * (MISSED_REPORTS + GRACE)));
    }

    /**
     * How many reports appear to have been missed, for the notification text.
     * Reported as a whole number, rounded down, so it never overstates.
     */
    public long missedReports() {
        return expectedInterval
                .filter(interval -> !interval.isZero())
                .map(interval -> sinceLast.toMillis() / interval.toMillis())
                .orElse(0L);
    }

    private static Optional<Duration> medianGap(List<Instant> arrivals) {
        if (arrivals.size() < MINIMUM_ARRIVALS) {
            return Optional.empty();
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < arrivals.size(); i++) {
            // Newest first, so the earlier index is the later instant.
            gaps.add(Duration.between(arrivals.get(i + 1), arrivals.get(i)).toMillis());
        }
        gaps.sort(null);
        int middle = gaps.size() / 2;
        long median = gaps.size() % 2 == 1
                ? gaps.get(middle)
                : (gaps.get(middle - 1) + gaps.get(middle)) / 2;
        return median <= 0 ? Optional.empty() : Optional.of(Duration.ofMillis(median));
    }
}
