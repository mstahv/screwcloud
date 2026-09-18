package fi.mstahv.sensorhub.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guess is a curve through a handful of anchors and a rule about the cold,
 * and those are what is checked: the anchors land where they were put, the
 * line between them runs the right way, and a cold tag is read as warmer than
 * it says before anyone calls it low.
 */
class BatteryEstimateTest {

    private static BatteryEstimate at(double volts, double temperature) {
        return BatteryEstimate.of(volts, temperature).orElseThrow();
    }

    @Test
    void aNewCellIsFullAndAnOldOneIsEmpty() {
        assertEquals(100, at(3.02, 20).percent(), "a fresh cell reads a little over 3.0");
        assertEquals(100, at(3.00, 20).percent());
        assertEquals(0, at(2.00, 20).percent());
        assertEquals(0, at(1.85, 20).percent(), "below the curve is still empty, not negative");
    }

    @Test
    void theAnchorsLandWhereRuuviPutThem() {
        assertEquals(80, at(2.90, 20).percent());
        assertEquals(55, at(2.80, 20).percent());
        assertEquals(25, at(2.70, 20).percent(), "the end of the plateau");
        assertEquals(5, at(2.50, 20).percent(), "Ruuvi's replace-it voltage at room temperature");
    }

    @Test
    void betweenTheAnchorsTheGuessRunsDownhillInFives() {
        int previous = 101;
        for (double volts = 3.0; volts >= 2.0; volts -= 0.01) {
            int percent = at(volts, 20).percent();
            assertTrue(percent <= previous, "more volts should never mean less battery");
            assertEquals(0, percent % 5, "rounded to fives, so it does not pretend to precision");
            previous = percent;
        }
        assertEquals(100, at(2.99, 20).percent(), "the tag on the desk, still as good as new");
        assertEquals(90, at(2.95, 20).percent());
    }

    @Test
    void lowIsRuuvisLineForTheTemperature() {
        assertTrue(at(2.49, 5).low());
        assertFalse(at(2.51, 5).low());

        // The same voltage in the frost is the cold talking, not the cell.
        assertFalse(at(2.49, -5).low());
        assertTrue(at(2.29, -5).low());

        assertFalse(at(2.29, -25).low());
        assertTrue(at(1.99, -25).low());
    }

    @Test
    void aColdTagIsReadAsWarmerBeforeTheGuess() {
        // 2.4 V at room temperature is nearly gone; in a freezer it is read as 2.9.
        assertEquals(5, at(2.40, 20).percent());
        assertEquals(80, at(2.40, -25).percent());
        assertEquals(15, at(2.40, -5).percent(), "read as 2.6, halfway down the cliff");
    }

    @Test
    void noTemperatureIsReadAsRoomTemperature() {
        assertEquals(at(2.6, 20).percent(), BatteryEstimate.of(2.6, null).orElseThrow().percent());
        assertTrue(BatteryEstimate.of(2.4, null).orElseThrow().low());
    }

    @Test
    void aSensorWithoutABatteryHasNoLevel() {
        assertTrue(BatteryEstimate.of(null, 20.0).isEmpty());
    }
}
