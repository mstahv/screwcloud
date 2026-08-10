package fi.mstahv.sensorhub.alerts;

import static fi.mstahv.sensorhub.store.TemperatureZone.ALERT_HIGH;
import static fi.mstahv.sensorhub.store.TemperatureZone.ALERT_LOW;
import static fi.mstahv.sensorhub.store.TemperatureZone.OK;
import static fi.mstahv.sensorhub.store.TemperatureZone.WARNING_HIGH;
import static fi.mstahv.sensorhub.store.TemperatureZone.WARNING_LOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import fi.mstahv.sensorhub.store.TemperatureZone;

/**
 * The rule that decides whether a phone buzzes. Pure, so it can be read as a
 * table of cases rather than inferred from a running application.
 */
class TemperatureAlertsTest {

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
    void leavingOkIsAnnounced() {
        assertEquals(WARNING_HIGH, announce(OK, WARNING_HIGH).orElseThrow());
        assertEquals(ALERT_LOW, announce(OK, ALERT_LOW).orElseThrow());
    }

    @Test
    void comingBackToOkIsAnnounced() {
        assertEquals(OK, announce(ALERT_LOW, OK).orElseThrow());
        assertEquals(OK, announce(WARNING_HIGH, OK).orElseThrow());
    }

    /*
       Getting worse and getting better are both changes. Improving from an alert
       to a warning is news to someone watching a freezer thaw or recover.
    */
    @Test
    void changesWithinTheSameSeverityDirectionAreAnnounced() {
        assertEquals(WARNING_LOW, announce(ALERT_LOW, WARNING_LOW).orElseThrow());
        assertEquals(ALERT_HIGH, announce(WARNING_HIGH, ALERT_HIGH).orElseThrow());
    }

    /*
       Crossing the whole OK band between two packets is one band change, not two.
       Both ends are "a warning", so a rule based on severity would have missed it.
    */
    @Test
    void aSwingFromOneWarningToTheOtherIsAnnounced() {
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
        assertTrue(TemperatureAlerts.transitionToAnnounce(Optional.empty(), OK).isEmpty());
        assertEquals(ALERT_HIGH,
                TemperatureAlerts.transitionToAnnounce(Optional.empty(), ALERT_HIGH).orElseThrow());
        assertEquals(WARNING_LOW,
                TemperatureAlerts.transitionToAnnounce(Optional.empty(), WARNING_LOW).orElseThrow());
    }

    private static Optional<TemperatureZone> announce(TemperatureZone previous, TemperatureZone current) {
        return TemperatureAlerts.transitionToAnnounce(Optional.of(previous), current);
    }
}
