package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure validation rules, no database needed.
 */
class SensorThresholdsTest {

    @Test
    void emptyIsValidAndMeansNoBands() {
        assertDoesNotThrow(SensorThresholds.NONE::validate);
        assertFalse(SensorThresholds.NONE.isConfigured());
    }

    @Test
    void increasingLimitsAreValid() {
        SensorThresholds bands = new SensorThresholds(-5.0, 2.0, 8.0, 15.0);

        assertDoesNotThrow(bands::validate);
        assertTrue(bands.isConfigured());
    }

    @Test
    void negativeLimitsAreFine() {
        // A freezer: OK well below zero.
        assertDoesNotThrow(new SensorThresholds(-30.0, -25.0, -18.0, -12.0)::validate);
    }

    @Test
    void partiallyFilledIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SensorThresholds(-5.0, 2.0, 8.0, null).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new SensorThresholds(null, 2.0, null, null).validate());
    }

    @Test
    void outOfOrderIsRejected() {
        // OK band inverted
        assertThrows(IllegalArgumentException.class,
                () -> new SensorThresholds(-5.0, 8.0, 2.0, 15.0).validate());
        // alert low above the OK band
        assertThrows(IllegalArgumentException.class,
                () -> new SensorThresholds(5.0, 2.0, 8.0, 15.0).validate());
        // alert high below the OK band
        assertThrows(IllegalArgumentException.class,
                () -> new SensorThresholds(-5.0, 2.0, 8.0, 6.0).validate());
    }

    /*
       The zone is what decides whether a notification is sent, so the boundaries
       are worth pinning down rather than trusting to read correctly.
    */
    @Test
    void everyBandHasItsZone() {
        SensorThresholds bands = new SensorThresholds(-5.0, 2.0, 8.0, 15.0);

        assertEquals(TemperatureZone.ALERT_LOW, bands.zoneOf(-10.0).orElseThrow());
        assertEquals(TemperatureZone.WARNING_LOW, bands.zoneOf(0.0).orElseThrow());
        assertEquals(TemperatureZone.OK, bands.zoneOf(5.0).orElseThrow());
        assertEquals(TemperatureZone.WARNING_HIGH, bands.zoneOf(12.0).orElseThrow());
        assertEquals(TemperatureZone.ALERT_HIGH, bands.zoneOf(20.0).orElseThrow());
    }

    /*
       A limit belongs to the calmer band. A reading sitting exactly on okLow is OK,
       not a warning: someone who set the OK band to start at 2 degrees does not
       want their phone buzzing at exactly 2 degrees.
    */
    @Test
    void limitsBelongToTheCalmerBand() {
        SensorThresholds bands = new SensorThresholds(-5.0, 2.0, 8.0, 15.0);

        assertEquals(TemperatureZone.OK, bands.zoneOf(2.0).orElseThrow());
        assertEquals(TemperatureZone.OK, bands.zoneOf(8.0).orElseThrow());
        assertEquals(TemperatureZone.WARNING_LOW, bands.zoneOf(-5.0).orElseThrow());
        assertEquals(TemperatureZone.WARNING_HIGH, bands.zoneOf(15.0).orElseThrow());
    }

    /*
       No bands means no alerts at all, which is what keeps notifications additive:
       a sensor nobody has configured limits for can never notify.
    */
    @Test
    void withoutBandsOrAReadingThereIsNoZone() {
        assertTrue(SensorThresholds.NONE.zoneOf(20.0).isEmpty());
        assertTrue(new SensorThresholds(-5.0, 2.0, 8.0, 15.0).zoneOf(null).isEmpty());
    }

    @Test
    void severityIgnoresDirection() {
        assertEquals(TemperatureZone.Severity.ALERT, TemperatureZone.ALERT_LOW.severity());
        assertEquals(TemperatureZone.Severity.ALERT, TemperatureZone.ALERT_HIGH.severity());
        assertEquals(TemperatureZone.Severity.WARNING, TemperatureZone.WARNING_LOW.severity());
        assertEquals(TemperatureZone.Severity.WARNING, TemperatureZone.WARNING_HIGH.severity());
        assertEquals(TemperatureZone.Severity.OK, TemperatureZone.OK.severity());
    }

    /*
       Equal limits would render as a zero-width arc, which looks like a bug
       rather than a configuration choice, so they are rejected too.
    */
    @Test
    void equalLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SensorThresholds(-5.0, 2.0, 2.0, 15.0).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new SensorThresholds(2.0, 2.0, 8.0, 15.0).validate());
    }
}
