package fi.mstahv.sensorhub.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.vaadin.svgvis.SvgSparkLine;

import fi.mstahv.sensorhub.store.HistoryPoint;

/**
 * Temperature history as a curve that fits on a card.
 *
 * <p>The same instance stays in place and the data is swapped via
 * {@link #setHistory}, so an update creates no new components.
 *
 * <p>The x axis spans exactly the measurements that are drawn, and the two ends
 * are labelled with their timestamps. Those labels are what keep the curve
 * honest: without them a sensor that has been up for an hour would look the same
 * as one with a full day of history.
 */
class TemperatureSparkLine extends SvgSparkLine {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 80;

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter CLOCK_WITH_DATE =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT);

    TemperatureSparkLine() {
        super(WIDTH, HEIGHT);
    }

    void setHistory(List<HistoryPoint> history) {
        // Missing readings are dropped, otherwise the curve would jump to zero.
        List<HistoryPoint> measured = history.stream()
                .filter(point -> point.temperature() != null)
                .toList();

        /*
           A single point is not a curve: it would draw as one dot with two
           identical timestamps under it, which says less than showing nothing.
           The whole element is hidden, labels included.
        */
        if (measured.size() < 2) {
            setVisible(false);
            return;
        }

        setVisible(true);
        setData(measured.stream().map(HistoryPoint::at).toArray(Instant[]::new),
                measured.stream().mapToDouble(HistoryPoint::temperature).toArray());

        /*
           The points are oldest first, which both the store and the sparkline's
           own x scaling already rely on: with no fixed x range it fits the axis
           to the first and last point. So these two labels are exactly the ends
           of the axis they are drawn under.
        */
        AxisLabels labels = axisLabels(measured.getFirst().at(), measured.getLast().at(),
                ClientTimeZone.get());
        setTimeScale(labels.start(), labels.end());

        /*
           draw() has to be called explicitly. setData only stores the data, and
           on an attached component draw() even clears it afterwards to save
           session memory. Without this call the curve would only ever update on
           the first attach.
        */
        draw();
    }

    /** The two texts drawn under the ends of the axis. */
    record AxisLabels(String start, String end) {
    }

    /**
     * @param zone the zone the times are rendered in — the reader's, not the
     *        server's, so that "different days" also means different days to them
     */
    static AxisLabels axisLabels(Instant first, Instant last, ZoneId zone) {
        /*
           The date is shown only when the two ends fall on different days.
           Within one day it is noise, and the labels are drawn at font size 10
           in a 200 unit wide viewBox — there is room for a clock time at each
           end, not for much more.
        */
        DateTimeFormatter format = localDate(first, zone).equals(localDate(last, zone))
                ? CLOCK
                : CLOCK_WITH_DATE;
        return new AxisLabels(format.format(first.atZone(zone)), format.format(last.atZone(zone)));
    }

    private static LocalDate localDate(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalDate();
    }
}
