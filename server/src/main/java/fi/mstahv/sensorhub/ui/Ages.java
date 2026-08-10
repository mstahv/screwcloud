package fi.mstahv.sensorhub.ui;

import java.time.Duration;
import java.time.Instant;

/**
 * Renders an arrival time as elapsed time. The devices have no clock, so an
 * absolute time would mislead — what matters is whether a reading is fresh.
 */
final class Ages {

    private Ages() {
    }

    static String format(Instant instant) {
        long seconds = Duration.between(instant, Instant.now()).toSeconds();
        if (seconds < 60) {
            return seconds + " s ago";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " min ago";
        }
        return (seconds / 3600) + " h ago";
    }
}
