package fi.mstahv.sensorhub.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a sensor identifier says about the sensor.
 *
 * <p>The packet carries no sensor type, so both of these are read from the
 * identifier — a naming convention the firmwares keep. They decide presentation
 * only: which reading is shown first, and which one is a diagnostic rather than a
 * measuring point.
 */
class SensorMeasurementTest {

    @Test
    void aTagIsRecognisedByItsDerivedIdentifier() {
        assertTrue(sensor("REBF").isRuuviTag());
        assertTrue(sensor("R84F").isRuuviTag(), "the current four character form");
        assertTrue(sensor("RBF").isRuuviTag(), "and the three character one before it");
    }

    @Test
    void theOtherSensorsAreNotTags() {
        assertFalse(sensor("DHT").isRuuviTag());
        assertFalse(sensor("CPU").isRuuviTag());
        assertFalse(sensor("R").isRuuviTag(), "an R on its own is not an address");
        assertFalse(sensor("ROOM").isRuuviTag(), "O and M are not hex digits");
        assertFalse(sensor("").isRuuviTag());
    }

    @Test
    void theChipIsRecognisedByItsIdentifier() {
        assertTrue(sensor("CPU").isDeviceInternal());
        assertFalse(sensor("DHT").isDeviceInternal());
        assertFalse(sensor("REBF").isDeviceInternal());
    }

    private static SensorMeasurement sensor(String id) {
        return new SensorMeasurement(id, 20.0, null);
    }
}
