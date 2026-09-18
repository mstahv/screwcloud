package fi.mstahv.sensorhub.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
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
 *
 * <p>Other readings can be laid over the temperature — humidity, the air, the
 * battery — each as a line in its own colour. They are drawn to the
 * temperature's scale, not their own: the component gives every series one
 * y axis, and 800 ppm on a 10 °C axis would be a straight line along the top
 * with the temperature flattened underneath. So each extra series is stretched
 * to run between the temperature's low and high, which keeps its <i>shape</i> —
 * the rise when the door opened, the fall overnight — and gives up its numbers,
 * which are on the card's own line below in any case. What the overlay is for
 * is seeing two things move together, and that is what survives.
 */
class TemperatureSparkLine extends SvgSparkLine {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 100;

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter CLOCK_WITH_DATE =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT);

    TemperatureSparkLine() {
        super(WIDTH, HEIGHT);
        setWidthFull();
    }

    void setHistory(List<HistoryPoint> history) {
        setHistory(history, List.of());
    }

    /**
     * @param extras the readings to lay over the temperature, in their own colours
     */
    void setHistory(List<HistoryPoint> history, Collection<ChartSeries> extras) {
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
           After setData, which starts the series list afresh, and before draw,
           which is what puts them on screen. Over the temperature's time span:
           the x axis is fixed by the first and last temperature, and a series
           drawn against its own first and last would slide along it.
        */
        double low = measured.stream().mapToDouble(HistoryPoint::temperature).min().orElse(0);
        double high = measured.stream().mapToDouble(HistoryPoint::temperature).max().orElse(0);
        setXRange(measured.getFirst().at(), measured.getLast().at());
        for (ChartSeries extra : extras) {
            overlay(history, extra, low, high);
        }
        clearXRange();

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

    /**
     * One extra series, stretched onto the temperature's range. Two points at
     * least, for the same reason as the temperature itself: one is a dot, not a
     * line. A flat series — the same value throughout — sits at the middle of
     * the range rather than dividing by zero.
     */
    private void overlay(List<HistoryPoint> history, ChartSeries extra, double low, double high) {
        List<HistoryPoint> present = history.stream()
                .filter(point -> extra.of(point) != null)
                .toList();
        if (present.size() < 2) {
            return;
        }
        double min = present.stream().mapToDouble(extra::of).min().orElseThrow();
        double max = present.stream().mapToDouble(extra::of).max().orElseThrow();
        double span = high - low;
        List<DataPoint> points = present.stream()
                .map(point -> DataPoint.of(point.at(), max - min < 1e-9
                        ? low + span / 2
                        : low + (extra.of(point) - min) / (max - min) * span))
                .toList();
        addSeries(points, extra.color());
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
           in a 400 unit wide viewBox — there is room for a clock time at each
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
