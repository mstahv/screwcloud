package fi.mstahv.sensorhub.ui;

import java.time.Duration;
import java.time.Instant;

import fi.mstahv.sensorhub.alerts.Elapsed;

/**
 * Renders an arrival time as elapsed time. The devices have no clock, so an
 * absolute time would mislead — what matters is whether a reading is fresh.
 *
 * <p>The wording comes from {@link Elapsed}, which the notifications share. Beyond
 * two days it now says "3 d" rather than "76 h".
 */
final class Ages {

    private Ages() {
    }

    static String format(Instant instant) {
        return Elapsed.approximate(Duration.between(instant, Instant.now())) + " ago";
    }
}
