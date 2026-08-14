package org.vaadin.example.ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.vaadin.svgvis.SvgSparkLine;

import org.vaadin.example.history.HistoryPoint;

/**
 * Temperature history as a curve that fits on a card. The server's card carries
 * the same one, from the same library, so the two read alike.
 *
 * <p>The same instance stays in place and the data is swapped via
 * {@link #setHistory}, so an update creates no new components.
 *
 * <p>The x axis spans exactly the measurements drawn, and both ends are labelled
 * with their times. Those labels are what keep the curve honest: without them a
 * reader that has been up for ten minutes looks exactly like one with a full day
 * behind it.
 *
 * <p>The times are the machine's own zone rather than the browser's. This page is
 * served by the Pi standing in the same house as whoever is looking at it, which
 * is not an assumption the server can make but is a safe one here.
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
        // Missing readings are dropped; otherwise the curve would dive to zero.
        List<HistoryPoint> measured = history.stream()
                .filter(point -> point.temperature() != null)
                .toList();

        /*
           A single point is not a curve. It would draw as one dot between two
           identical timestamps, which says less than showing nothing at all — so
           the whole element goes, labels included.
        */
        if (measured.size() < 2) {
            setVisible(false);
            return;
        }

        setVisible(true);
        setData(measured.stream().map(HistoryPoint::at).toArray(Instant[]::new),
                measured.stream().mapToDouble(HistoryPoint::temperature).toArray());

        AxisLabels labels = axisLabels(measured.getFirst().at(), measured.getLast().at(),
                ZoneId.systemDefault());
        setTimeScale(labels.start(), labels.end());

        /*
           draw() has to be called explicitly: setData only stores the data, and on
           an attached component draw() clears it afterwards to save session memory.
           Without this the curve would only ever update on the first attach.
        */
        draw();
    }

    /** The two texts drawn under the ends of the axis. */
    record AxisLabels(String start, String end) {
    }

    static AxisLabels axisLabels(Instant first, Instant last, ZoneId zone) {
        /*
           The date only when the ends fall on different days. Within one day it is
           noise, and these are drawn at font size 10 in a 400 unit viewBox — there
           is room for a clock time at each end and not much more.
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
