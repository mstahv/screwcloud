package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The en dash convention, which two components now depend on.
 */
class ReadingsTest {

    @Test
    void aMissingValueIsADashAndNotAZero() {
        assertEquals("–", Readings.format(null, "%.2f"));
    }

    @Test
    void zeroIsARealReading() {
        assertEquals("0.00", Readings.format(0.0, "%.2f"));
    }

    /*
       The pattern is applied with Locale.ROOT, so the decimal separator does not
       follow the machine's locale — a Finnish default would render "20,53" while
       the rest of the UI is in English.
    */
    @Test
    void decimalSeparatorDoesNotFollowTheServerLocale() {
        assertEquals("20.53", Readings.format(20.53, "%.2f"));
    }
}
