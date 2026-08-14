package fi.mstahv.sensorhub.alerts;

import static fi.mstahv.sensorhub.store.TemperatureZone.ALERT_HIGH;
import static fi.mstahv.sensorhub.store.TemperatureZone.ALERT_LOW;
import static fi.mstahv.sensorhub.store.TemperatureZone.OK;
import static fi.mstahv.sensorhub.store.TemperatureZone.WARNING_HIGH;
import static fi.mstahv.sensorhub.store.TemperatureZone.WARNING_LOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import fi.mstahv.sensorhub.alerts.TemperatureAlerts.ZoneAt;
import fi.mstahv.sensorhub.store.TemperatureZone;

/**
 * The rule that decides whether a phone buzzes. Pure, so it can be read as a
 * table of cases rather than inferred from a running application.
 *
 * <p>The readings are given five minutes apart, which is what the devices send,
 * and the settling time is an hour — so it takes twelve calm readings to earn a
 * "back to normal".
 */
class TemperatureAlertsTest {

    private static final Instant START = Instant.parse("2026-08-14T06:00:00Z");
    private static final Duration EVERY_FIVE_MINUTES = Duration.ofMinutes(5);

    /*
       The whole point of tracking transitions: a sensor that stays too warm for a
       day sends one notification, not one per packet.
    */
    @Test
    void stayingInTheSameBandIsSilent() {
        assertTrue(announce(ALERT_HIGH, ALERT_HIGH).isEmpty());
        assertTrue(announce(OK, OK).isEmpty());
        assertTrue(announce(WARNING_LOW, WARNING_LOW).isEmpty());
    }

    @Test
    void leavingOkIsAnnouncedAtOnce() {
        assertEquals(WARNING_HIGH, announce(OK, WARNING_HIGH).orElseThrow());
        assertEquals(ALERT_LOW, announce(OK, ALERT_LOW).orElseThrow());
    }

    /*
       Getting worse is news the moment it happens, whatever the sensor did before.
    */
    @Test
    void gettingWorseIsAnnouncedAtOnce() {
        assertEquals(ALERT_HIGH, announce(WARNING_HIGH, ALERT_HIGH).orElseThrow());
        assertEquals(ALERT_LOW, announce(WARNING_LOW, ALERT_LOW).orElseThrow());
    }

    /*
       Crossing the whole OK band between two packets is one band change, not two.
       Both ends are "a warning", so a rule based on severity alone would have missed
       it — and it is not calming down, so it does not wait.
    */
    @Test
    void aSwingFromOneWarningToTheOtherIsAnnouncedAtOnce() {
        assertEquals(WARNING_HIGH, announce(WARNING_LOW, WARNING_HIGH).orElseThrow());
    }

    /*
       A first reading is not a transition. Announcing an OK one would fire for
       every sensor the first time it reports, which is noise — but a freezer that
       is already too warm the first time it reports is exactly what an alert
       subscriber wants to know.
    */
    @Test
    void aFirstReadingIsAnnouncedOnlyWhenItIsNotOk() {
        assertTrue(announce(OK).isEmpty());
        assertEquals(ALERT_HIGH, announce(ALERT_HIGH).orElseThrow());
        assertEquals(WARNING_LOW, announce(WARNING_LOW).orElseThrow());
    }

    // ------------------------------------------------------------------
    // Calming down
    // ------------------------------------------------------------------

    /** One calm reading is not a recovery. It is what a sensor on a limit does. */
    @Test
    void oneCalmReadingIsNotEnough() {
        assertTrue(announce(WARNING_HIGH, OK).isEmpty());
        assertTrue(announce(ALERT_HIGH, WARNING_HIGH).isEmpty());
    }

    @Test
    void anHourOfCalmIsAnnounced() {
        List<TemperatureZone> readings = new ArrayList<>(List.of(WARNING_HIGH));
        readings.addAll(twelveOf(OK));

        assertEquals(OK, announce(readings).orElseThrow());
    }

    /** And not a reading sooner: eleven calm readings are fifty-five minutes. */
    @Test
    void anHourMeansAnHour() {
        List<TemperatureZone> readings = new ArrayList<>(List.of(WARNING_HIGH));
        readings.addAll(elevenOf(OK));

        assertTrue(announce(readings).isEmpty());
    }

    /**
     * The morning this was written for: a reading sitting on a limit crossed it
     * every few minutes, and every crossing was a notification. Now the first one
     * is, and the rest are the same sensor still being on the same limit.
     */
    @Test
    void aReadingSawingOnALimitIsAnnouncedOnce() {
        List<TemperatureZone> sawing = new ArrayList<>(List.of(OK));
        for (int i = 0; i < 6; i++) {
            sawing.add(WARNING_HIGH);
            sawing.add(OK);
        }

        // The one that leaves OK, and nothing after it: each return to the warning
        // ends the calm before the hour is up.
        assertEquals(WARNING_HIGH, announce(sawing.subList(0, 2)).orElseThrow());
        assertTrue(announce(sawing).isEmpty(), "the twelve crossings after it are silent");
    }

    /**
     * What the reader is told is where the sensor is now, not every band it passed
     * through on the way down. Coming from an alert through a warning to OK inside
     * the settling hour, the warning was never news.
     */
    @Test
    void onlyTheBandItEndsUpInIsAnnounced() {
        List<TemperatureZone> comingDown = new ArrayList<>(List.of(ALERT_HIGH));
        comingDown.addAll(sixOf(WARNING_HIGH));
        comingDown.addAll(sixOf(OK));

        assertEquals(OK, announce(comingDown).orElseThrow(),
                "an hour after leaving the alert, and it is OK by then");
    }

    /** Wandering between calmer bands does not restart the clock; returning does. */
    @Test
    void returningToTheAnnouncedBandRestartsTheWait() {
        List<TemperatureZone> readings = new ArrayList<>(List.of(ALERT_HIGH));
        readings.addAll(sixOf(OK));
        readings.add(ALERT_HIGH);          // back where it was: the calm ended
        readings.addAll(sixOf(OK));        // only half an hour of calm since

        assertTrue(announce(readings).isEmpty());
    }

    /** And getting worse during the wait is still announced the moment it happens. */
    @Test
    void gettingWorseDuringTheWaitIsAnnouncedAtOnce() {
        List<TemperatureZone> readings = new ArrayList<>(List.of(WARNING_HIGH));
        readings.addAll(sixOf(OK));
        readings.add(ALERT_HIGH);

        assertEquals(ALERT_HIGH, announce(readings).orElseThrow());
    }

    // ------------------------------------------------------------------

    private static List<TemperatureZone> twelveOf(TemperatureZone zone) {
        return List.of(zone, zone, zone, zone, zone, zone, zone, zone, zone, zone, zone, zone);
    }

    private static List<TemperatureZone> elevenOf(TemperatureZone zone) {
        return twelveOf(zone).subList(0, 11);
    }

    private static List<TemperatureZone> sixOf(TemperatureZone zone) {
        return twelveOf(zone).subList(0, 6);
    }

    private static Optional<TemperatureZone> announce(TemperatureZone... zones) {
        return announce(List.of(zones));
    }

    /** The zones as readings five minutes apart, oldest first. */
    private static Optional<TemperatureZone> announce(List<TemperatureZone> zones) {
        List<ZoneAt> readings = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) {
            readings.add(new ZoneAt(START.plus(EVERY_FIVE_MINUTES.multipliedBy(i)), zones.get(i)));
        }
        return TemperatureAlerts.transitionToAnnounce(readings, TemperatureAlerts.SETTLE);
    }
}
