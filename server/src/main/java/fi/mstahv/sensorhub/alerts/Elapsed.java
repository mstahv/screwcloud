package fi.mstahv.sensorhub.alerts;

import java.time.Duration;

/**
 * A duration as a short phrase: "45 s", "22 min", "3 h", "2 d".
 *
 * <p>Deliberately coarse. These are read at a glance, in a notification or under a
 * reading, where the difference between 21 and 22 minutes changes nothing.
 *
 * <p>Lives here rather than in the UI package because the notifications need it
 * too, and a background sweep should not be reaching into the views for a
 * formatter.
 */
public final class Elapsed {

    private Elapsed() {
    }

    public static String approximate(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " min";
        }
        if (seconds < 48 * 3600) {
            return (seconds / 3600) + " h";
        }
        return (seconds / 86400) + " d";
    }
}
