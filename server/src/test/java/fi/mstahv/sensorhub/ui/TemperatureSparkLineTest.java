package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import fi.mstahv.sensorhub.store.HistoryPoint;
import fi.mstahv.sensorhub.ui.TemperatureSparkLine.AxisLabels;

/**
 * No Spring context and no database: the curve only needs a list of points.
 */
class TemperatureSparkLineTest {

    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private final TemperatureSparkLine sparkLine = new TemperatureSparkLine();

    @Test
    void nothingToDrawIsHidden() {
        sparkLine.setHistory(List.of());

        assertFalse(sparkLine.isVisible());
    }

    @Test
    void singlePointIsHidden() {
        // One point is a dot, not a curve, and both axis labels would be the same.
        sparkLine.setHistory(List.of(point(at(12, 0), 20.0)));

        assertFalse(sparkLine.isVisible());
    }

    /*
       The count that matters is of points that have a temperature, not of rows:
       a sensor that reports humidity only would otherwise draw a curve out of
       nothing.
    */
    @Test
    void pointsWithoutTemperatureDoNotCount() {
        sparkLine.setHistory(List.of(
                point(at(12, 0), 20.0),
                point(at(13, 0), null),
                point(at(14, 0), null)));

        assertFalse(sparkLine.isVisible());
    }

    @Test
    void twoMeasuredPointsAreDrawn() {
        sparkLine.setHistory(List.of(
                point(at(12, 0), 20.0),
                point(at(13, 0), 21.0)));

        assertTrue(sparkLine.isVisible());
    }

    /*
       Visibility has to fall back as well: switching devices reuses the card, and
       a curve left over from the previous sensor would be read as this one's.
    */
    @Test
    void becomesHiddenAgainWhenHistoryShrinks() {
        sparkLine.setHistory(List.of(point(at(12, 0), 20.0), point(at(13, 0), 21.0)));

        sparkLine.setHistory(List.of(point(at(12, 0), 20.0)));

        assertFalse(sparkLine.isVisible());
    }

    /*
       The labels are drawn into the SVG by the sparkline's own time scale
       support, not added as separate components, so this checks that they
       actually reach the element tree.
    */
    @Test
    void theLabelsAreDrawnIntoTheCurve() {
        Instant first = at(9, 5);
        Instant last = at(18, 20);

        sparkLine.setHistory(List.of(point(first, 20.0), point(last, 21.0)));

        /*
           With no UI there is no browser zone, so the component falls back to
           this JVM's. The expected texts are therefore asked for in the same
           zone: what is being checked here is that the labels reach the element
           tree at all, not how they are formatted.
        */
        AxisLabels expected = TemperatureSparkLine.axisLabels(first, last, ZoneId.systemDefault());
        String svg = sparkLine.getElement().getTextRecursively();

        assertTrue(svg.contains(expected.start()), svg);
        assertTrue(svg.contains(expected.end()), svg);
    }

    @Test
    void axisEndsAreLabelledWithClockTimeWithinOneDay() {
        AxisLabels labels = TemperatureSparkLine.axisLabels(at(9, 5), at(18, 20), HELSINKI);

        assertEquals("09:05", labels.start());
        assertEquals("18:20", labels.end());
    }

    @Test
    void axisLabelsCarryTheDateWhenTheSpanCrossesMidnight() {
        AxisLabels labels = TemperatureSparkLine.axisLabels(
                at(LocalDate.of(2026, 8, 9), 14, 5),
                at(LocalDate.of(2026, 8, 10), 13, 55),
                HELSINKI);

        assertEquals("Aug 9 14:05", labels.start());
        assertEquals("Aug 10 13:55", labels.end());
    }

    /*
       The same instants read differently to a reader elsewhere, which is the
       whole reason the zone comes from the browser rather than from the server.
    */
    @Test
    void labelsFollowTheReadersZone() {
        AxisLabels labels = TemperatureSparkLine.axisLabels(
                at(9, 5), at(18, 20), ZoneId.of("America/New_York"));

        assertEquals("02:05", labels.start());
        assertEquals("11:20", labels.end());
    }

    /*
       Midnight is a property of the zone too. Tokyo's falls at 18:00 Helsinki
       time in summer, so this hour is one Helsinki day but two Tokyo days — and
       only the reader in Tokyo needs to see a date.
    */
    @Test
    void whetherTheDateIsNeededDependsOnTheZone() {
        Instant beforeTokyoMidnight = at(17, 30);
        Instant afterTokyoMidnight = at(18, 30);

        assertEquals("17:30", TemperatureSparkLine
                .axisLabels(beforeTokyoMidnight, afterTokyoMidnight, HELSINKI).start());
        assertEquals("Aug 10 23:30", TemperatureSparkLine
                .axisLabels(beforeTokyoMidnight, afterTokyoMidnight, TOKYO).start());
        assertEquals("Aug 11 00:30", TemperatureSparkLine
                .axisLabels(beforeTokyoMidnight, afterTokyoMidnight, TOKYO).end());
    }

    private static HistoryPoint point(Instant at, Double temperature) {
        return new HistoryPoint(at, temperature, 40.0);
    }

    /*
       Instants are built from Helsinki wall clock times and the expected labels
       are asserted in an explicit zone, so nothing here depends on the zone of
       the machine running the test.
    */
    private static Instant at(int hour, int minute) {
        return at(LocalDate.of(2026, 8, 10), hour, minute);
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(LocalTime.of(hour, minute)).atZone(HELSINKI).toInstant();
    }
}
