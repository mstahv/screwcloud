package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;

@DatabaseTest
@Import({TestDatabase.class, SensorSettingsStore.class})
class SensorSettingsStoreTest {

    @Autowired
    private SensorSettingsStore store;

    @Test
    void unsetSensorHasNoNameAndNoBands() {
        assertNull(store.nameFor("LAHT", "DHT"));
        assertFalse(store.thresholdsFor("LAHT", "DHT").isConfigured());
    }

    @Test
    void bandsRoundTrip() {
        store.setThresholds("LAHT", "DHT", new SensorThresholds(-5.0, 2.0, 8.0, 15.0));

        SensorThresholds stored = store.thresholdsFor("LAHT", "DHT");

        assertTrue(stored.isConfigured());
        assertEquals(-5.0, stored.alertLow());
        assertEquals(2.0, stored.okLow());
        assertEquals(8.0, stored.okHigh());
        assertEquals(15.0, stored.alertHigh());
    }

    @Test
    void bandsAndNameAreIndependent() {
        store.rename("LAHT", "DHT", "Cold room");
        store.setThresholds("LAHT", "DHT", new SensorThresholds(-5.0, 2.0, 8.0, 15.0));

        assertEquals("Cold room", store.nameFor("LAHT", "DHT"));
        assertTrue(store.thresholdsFor("LAHT", "DHT").isConfigured());

        // Renaming must not wipe the bands, nor the other way round.
        store.rename("LAHT", "DHT", "Cellar");
        assertTrue(store.thresholdsFor("LAHT", "DHT").isConfigured());

        store.setThresholds("LAHT", "DHT", new SensorThresholds(0.0, 5.0, 10.0, 20.0));
        assertEquals("Cellar", store.nameFor("LAHT", "DHT"));
    }

    @Test
    void emptyBandsClearStoredOnes() {
        store.setThresholds("LAHT", "DHT", new SensorThresholds(-5.0, 2.0, 8.0, 15.0));

        store.setThresholds("LAHT", "DHT", SensorThresholds.NONE);

        assertFalse(store.thresholdsFor("LAHT", "DHT").isConfigured());
    }

    /*
       Validation lives in the store rather than only in the form, so the rule
       holds regardless of who calls.
    */
    @Test
    void invalidBandsAreRejectedBeforeStoring() {
        assertThrows(ConstraintViolationException.class,
                () -> store.setThresholds("LAHT", "DHT", new SensorThresholds(-5.0, 8.0, 2.0, 15.0)));

        assertFalse(store.thresholdsFor("LAHT", "DHT").isConfigured());
    }

    @Test
    void bandsAreScopedToDeviceAndSensor() {
        store.setThresholds("LAHT", "DHT", new SensorThresholds(-5.0, 2.0, 8.0, 15.0));

        // Same sensor id on another device must be unaffected.
        assertFalse(store.thresholdsFor("TALO", "DHT").isConfigured());
        // Another sensor on the same device too.
        assertFalse(store.thresholdsFor("LAHT", "RBF").isConfigured());
    }
}
