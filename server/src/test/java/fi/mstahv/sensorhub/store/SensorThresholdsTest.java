package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

/**
 * Pure validation rules, no database needed.
 *
 * <p>Driven through a plain {@link Validator} rather than through a store, because
 * that is what everything else does with these: Hibernate before an insert, the
 * store before it saves, and the settings form on every keystroke. If the rule holds
 * here it holds in all three.
 */
class SensorThresholdsTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void emptyIsValidAndMeansNoBands() {
        assertTrue(messages(SensorThresholds.NONE).isEmpty());
        assertFalse(SensorThresholds.NONE.isConfigured());
    }

    @Test
    void increasingLimitsAreValid() {
        SensorThresholds bands = new SensorThresholds(-5.0, 2.0, 8.0, 15.0);

        assertTrue(messages(bands).isEmpty());
        assertTrue(bands.isConfigured());
    }

    @Test
    void negativeLimitsAreFine() {
        // A freezer: OK well below zero.
        assertTrue(messages(new SensorThresholds(-30.0, -25.0, -18.0, -12.0)).isEmpty());
    }

    /*
       The message matters as much as the refusal: "not in order" is no help to
       someone who has filled in three fields of four.
    */
    @Test
    void partiallyFilledIsRejected() {
        assertEquals(List.of("Give all four temperature limits, or leave them all empty"),
                messages(new SensorThresholds(-5.0, 2.0, 8.0, null)));
        assertEquals(List.of("Give all four temperature limits, or leave them all empty"),
                messages(new SensorThresholds(null, 2.0, null, null)));
    }

    @Test
    void outOfOrderIsRejected() {
        // OK band inverted
        assertEquals(
                List.of("The limits must increase: alert low < OK low < OK high < alert high"),
                messages(new SensorThresholds(-5.0, 8.0, 2.0, 15.0)));
        // alert low above the OK band
        assertFalse(messages(new SensorThresholds(5.0, 2.0, 8.0, 15.0)).isEmpty());
        // alert high below the OK band
        assertFalse(messages(new SensorThresholds(-5.0, 2.0, 8.0, 6.0)).isEmpty());
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
        assertFalse(messages(new SensorThresholds(-5.0, 2.0, 2.0, 15.0)).isEmpty());
        assertFalse(messages(new SensorThresholds(2.0, 2.0, 8.0, 15.0)).isEmpty());
    }

    /** What a reader would be shown, which is the point of a class level constraint. */
    private static List<String> messages(SensorThresholds bands) {
        Set<ConstraintViolation<SensorThresholds>> violations = VALIDATOR.validate(bands);
        return violations.stream().map(ConstraintViolation::getMessage).sorted().toList();
    }
}
