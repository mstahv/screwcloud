package fi.mstahv.sensorhub.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A moment in the future as a phrase a reader can plan around.
 *
 * <p>"Sat 09:15" answers "will I be there when it is ready"; an ISO timestamp does
 * not. Anything within a week is named by its weekday, because that is how the next
 * few days are thought about, and beyond that by date.
 */
final class TimeText {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter WEEKDAY_AND_CLOCK =
            DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE_AND_CLOCK =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT);

    private TimeText() {
    }

    /** In the reader's own zone, as everywhere else a time is shown. */
    static String dayAndTime(Instant when) {
        return dayAndTime(when, Instant.now(), ClientTimeZone.get());
    }

    static String dayAndTime(Instant when, Instant now, ZoneId zone) {
        ZonedDateTime target = when.atZone(zone);
        LocalDate today = now.atZone(zone).toLocalDate();
        long days = Duration.between(today.atStartOfDay(zone), target.toLocalDate().atStartOfDay(zone))
                .toDays();

        if (days <= 0) {
            return "today " + CLOCK.format(target);
        }
        if (days == 1) {
            return "tomorrow " + CLOCK.format(target);
        }
        /*
           Beyond a week a weekday is ambiguous — "Sat" could be this one or the next
           — so it becomes a date.
        */
        return days < 7 ? WEEKDAY_AND_CLOCK.format(target) : DATE_AND_CLOCK.format(target);
    }
}
