package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * The forecast wording. No UI needed: the zone is a parameter, so the expectations
 * do not depend on where the test runs.
 */
class TimeTextTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    // 2026-08-10 is a Monday; 15:00 Helsinki time.
    private static final Instant MONDAY_AFTERNOON = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void laterTodayIsNamedToday() {
        assertEquals("today 20:30", at(Duration.ofHours(5).plusMinutes(30)));
    }

    @Test
    void thenTomorrow() {
        assertEquals("tomorrow 09:00", at(Duration.ofHours(18)));
    }

    /*
       Within the week a weekday is what someone plans around: "will I be there on
       Saturday" is the actual question.
    */
    @Test
    void withinTheWeekItIsTheWeekday() {
        assertEquals("Thu 15:00", at(Duration.ofDays(3)));
        assertEquals("Sat 15:00", at(Duration.ofDays(5)));
    }

    /*
       Beyond a week a weekday would be ambiguous — "Sat" could be this one or the
       next — so it becomes a date.
    */
    @Test
    void beyondTheWeekItIsADate() {
        assertEquals("Aug 20 15:00", at(Duration.ofDays(10)));
        assertEquals("Sep 9 15:00", at(Duration.ofDays(30)));
    }

    @Test
    void aTimeAlreadyPastReadsAsToday() {
        assertEquals("today 15:00", TimeText.dayAndTime(
                MONDAY_AFTERNOON, MONDAY_AFTERNOON.plus(Duration.ofHours(2)), HELSINKI));
    }

    private static String at(Duration fromNow) {
        return TimeText.dayAndTime(MONDAY_AFTERNOON.plus(fromNow), MONDAY_AFTERNOON, HELSINKI);
    }
}
