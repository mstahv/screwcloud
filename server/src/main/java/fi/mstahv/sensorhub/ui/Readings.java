package fi.mstahv.sensorhub.ui;

import java.util.Locale;

/**
 * Formatting of sensor values, shared by everything that shows them.
 *
 * <p>The one rule worth having in a single place: a value the sensor did not
 * report is an en dash, never a zero. Zero is a real temperature.
 */
final class Readings {

    static final String MISSING = "–";

    private Readings() {
    }

    static String format(Double value, String pattern) {
        return value == null ? MISSING : String.format(Locale.ROOT, pattern, value);
    }
}
