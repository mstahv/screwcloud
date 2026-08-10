package fi.mstahv.sensorhub.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The rule behind both the offline badge and the notification. Pure, so it reads
 * as a table of cases.
 */
class DeviceActivityTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @Test
    void theRhythmIsLearnedFromTheArrivals() {
        DeviceActivity activity = DeviceActivity.of(every(FIVE_MINUTES, 6, NOW), NOW);

        assertEquals(FIVE_MINUTES, activity.expectedInterval().orElseThrow());
        assertFalse(activity.silent());
    }

    /*
       The median, not the mean: a single long gap is exactly what happens when a
       send is lost, and it must not teach the server that the device is slow.
    */
    @Test
    void oneMissedSendDoesNotChangeTheLearnedRhythm() {
        List<Instant> arrivals = List.of(
                NOW,
                NOW.minus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofMinutes(10)),
                // a gap of 20 minutes here: three sends were lost
                NOW.minus(Duration.ofMinutes(30)),
                NOW.minus(Duration.ofMinutes(35)),
                NOW.minus(Duration.ofMinutes(40)));

        assertEquals(FIVE_MINUTES,
                DeviceActivity.of(arrivals, NOW).expectedInterval().orElseThrow());
    }

    @Test
    void aDeviceReportingOnTimeIsNotSilent() {
        List<Instant> arrivals = every(FIVE_MINUTES, 6, NOW.minus(Duration.ofMinutes(4)));

        assertFalse(DeviceActivity.of(arrivals, NOW).silent());
    }

    /*
       One or two missed reports are not news: a lost UDP packet or a modem retry
       looks exactly like this.
    */
    @Test
    void oneOrTwoMissedReportsAreTolerated() {
        assertFalse(silentAfter(Duration.ofMinutes(6)));
        assertFalse(silentAfter(Duration.ofMinutes(12)));
    }

    @Test
    void threeMissedReportsPlusGraceIsSilent() {
        // The threshold is 3.5 intervals: 17.5 minutes for a five-minute device.
        assertFalse(silentAfter(Duration.ofMinutes(17)));
        assertTrue(silentAfter(Duration.ofMinutes(18)));
        assertTrue(silentAfter(Duration.ofHours(3)));
    }

    /*
       The threshold follows the device's own rhythm: an hourly device is not
       offline after 18 minutes.
    */
    @Test
    void theThresholdScalesWithTheRhythm() {
        List<Instant> hourly = every(Duration.ofHours(1), 6, NOW.minus(Duration.ofMinutes(90)));

        assertFalse(DeviceActivity.of(hourly, NOW).silent());
        assertEquals(Duration.ofMinutes(210), DeviceActivity.threshold(Duration.ofHours(1)));
    }

    /*
       A device that has just been set up has no rhythm yet, and guessing one would
       mean reporting it offline before it ever had a chance.
    */
    @Test
    void tooLittleHistoryMeansNoJudgement() {
        List<Instant> two = List.of(NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(6)));

        DeviceActivity activity = DeviceActivity.of(two, NOW);

        assertTrue(activity.expectedInterval().isEmpty());
        assertFalse(activity.silent(), "Without a known rhythm nothing should be declared silent");
    }

    @Test
    void neverHeardFromIsNotSilentEither() {
        assertFalse(DeviceActivity.of(List.of(), NOW).silent());
        assertFalse(DeviceActivity.neverHeard().silent());
    }

    @Test
    void theNumberOfMissedReportsIsRoundedDown() {
        List<Instant> arrivals = every(FIVE_MINUTES, 6, NOW.minus(Duration.ofMinutes(32)));

        // 32 minutes at five-minute intervals is six missed, not six and a half.
        assertEquals(6, DeviceActivity.of(arrivals, NOW).missedReports());
    }

    private static boolean silentAfter(Duration quiet) {
        return DeviceActivity.of(every(FIVE_MINUTES, 6, NOW.minus(quiet)), NOW).silent();
    }

    /** Arrivals newest first, the newest at {@code latest}. */
    private static List<Instant> every(Duration interval, int count, Instant latest) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> latest.minus(interval.multipliedBy(i)))
                .toList();
    }
}
