package fi.mstahv.sensorhub.ui;

import java.util.function.Consumer;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.dom.Style;

import fi.mstahv.sensorhub.protocol.SensorMeasurement;

/**
 * One reading under the curve, with the switch that lays it over the curve.
 *
 * <p>A row rather than a span: the text on the left, and at the right edge a
 * small chart icon that toggles the reading onto the temperature curve as a
 * second line. While the line is on, the icon and the text take the line's
 * colour, so the row doubles as the legend and nothing else has to explain
 * which line is which.
 *
 * <p>Hidden, not dashed, when the sensor has no such value: a dash means
 * "measured and missing", and a plain tag would otherwise carry permanent
 * dashes for instruments it does not have.
 */
class ExtraReading extends Div {

    private final ChartSeries series;
    private final SecondaryText text = new SecondaryText();
    private final Toggle toggle;
    private boolean onChart;

    /**
     * @param onToggle told when the reader switches the line on or off; the card
     *        redraws the curve
     */
    ExtraReading(ChartSeries series, Consumer<ChartSeries> onToggle) {
        this.series = series;
        this.toggle = new Toggle(() -> {
            setOnChart(!onChart);
            onToggle.accept(series);
        });
        getStyle().setDisplay(Style.Display.FLEX);
        getStyle().setAlignItems(Style.AlignItems.CENTER);
        getStyle().setJustifyContent(Style.JustifyContent.SPACE_BETWEEN);
        add(text, toggle);
        setVisible(false);
    }

    ChartSeries series() {
        return series;
    }

    boolean isOnChart() {
        return onChart;
    }

    /** The latest reading, or nothing at all for a sensor without one. */
    void show(SensorMeasurement sensor) {
        setVisible(series.of(sensor) != null);
        text.setText(series.text(sensor));
    }

    private void setOnChart(boolean on) {
        onChart = on;
        toggle.reflect(on);
        // The row is the legend: it wears the line's colour while the line is on.
        if (on) {
            text.getStyle().setColor(series.cssColor());
        } else {
            text.getStyle().remove("color");
        }
    }

    /**
     * The chart icon. Inline and small, so it sits on the text line rather than
     * making one of its own; coloured like the series while it is on, and in the
     * secondary text colour when it is not — a hint of a control, not a button
     * competing with the cog above.
     */
    private class Toggle extends Button {
        Toggle(Runnable onClick) {
            setIcon(VaadinIcon.LINE_CHART.create());
            addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            addClickListener(click -> onClick.run());
            reflect(false);
        }

        void reflect(boolean on) {
            setAriaLabel((on ? "Hide " : "Show ") + series.caption() + " on the chart");
            if (on) {
                getStyle().setColor(series.cssColor());
            } else {
                getStyle().setColor("var(--vaadin-text-color-secondary)");
            }
        }
    }
}
