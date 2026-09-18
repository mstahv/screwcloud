package fi.mstahv.sensorhub.ui;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.Table;
import com.vaadin.flow.component.html.TableDataCell;
import com.vaadin.flow.component.html.TableHeaderCell;
import com.vaadin.flow.component.html.TableRow;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.dom.Style;

import fi.mstahv.sensorhub.protocol.SensorMeasurement;

/**
 * The readings under a sensor card's curve, as a table: what it is, what it
 * reads, and the switch that lays it over the curve.
 *
 * <p>A table because that is what this is — three things that are the same
 * three things on every row — and because the first version, a line of text per
 * reading with the unit at the end, read as prose: "801 ppm CO2" and "0.5 ug/m3
 * PM2.5" one under the other made the eye find the name at the end of each
 * line. With the names in a column of their own the values line up and the
 * table is read down, the way a table is.
 *
 * <p>Each row is one {@link ChartSeries} and shows itself only for a sensor that
 * has the value; a plain tag has two rows, an Air four. The battery's value
 * carries a small drawn battery ({@link BatteryLevel}) before the number.
 *
 * <p>The chart switch at the end of a row lays that reading over the temperature
 * curve in its own colour, and the row's name takes the same colour while the
 * line is on — the table is the legend.
 */
class ReadingsTable extends Table {

    private final Map<ChartSeries, ReadingRow> rows = new EnumMap<>(ChartSeries.class);

    /**
     * @param onToggle told when a row's line is switched on or off; the card
     *        redraws the curve
     */
    ReadingsTable(Consumer<ChartSeries> onToggle) {
        /*
           A table inside a card, not a spreadsheet: full width so the switches
           line up at the card's edge, no borders, and rows packed like lines of
           text, which is what they replace.
        */
        setWidthFull();
        getStyle().set("border-collapse", "collapse");
        for (ChartSeries series : ChartSeries.values()) {
            ReadingRow row = new ReadingRow(series, onToggle);
            rows.put(series, row);
            getBody().add(row);
        }
    }

    /** The latest reading on every row, and only the rows the sensor has. */
    void show(SensorMeasurement sensor) {
        rows.values().forEach(row -> row.show(sensor));
    }

    /** The readings whose line is switched on, in table order. */
    List<ChartSeries> onChart() {
        return rows.values().stream()
                .filter(ReadingRow::isOnChart)
                .map(ReadingRow::series)
                .toList();
    }

    /** One reading: its name as the row's header, its value, its switch. */
    private static class ReadingRow extends TableRow {

        private final ChartSeries series;
        private final TableHeaderCell name;
        private final Span value = new Span();
        private final BatteryLevel battery = new BatteryLevel();
        private final Toggle toggle;
        private boolean onChart;

        ReadingRow(ChartSeries series, Consumer<ChartSeries> onToggle) {
            this.series = series;
            this.toggle = new Toggle(() -> {
                setOnChart(!onChart);
                onToggle.accept(series);
            });

            /*
               A row header, because the name is what the row is about, and the
               document outline should say so — a screen reader announces it
               with the value. Styled as the quiet half of the row: the value is
               what a reader came for.
            */
            name = addRowHeaderCell(series.label());
            name.getStyle().setFontWeight(Style.FontWeight.NORMAL);
            name.getStyle().setTextAlign(Style.TextAlign.LEFT);
            name.getStyle().setWhiteSpace(Style.WhiteSpace.NOWRAP);
            name.getStyle().setColor("var(--vaadin-text-color-secondary)");
            name.getStyle().setWidth("6rem");
            pad(name);

            /*
               The battery's drawing sits before its number and is hidden on
               every other row — one cell layout for all four rows, rather than a
               row that is shaped differently.
            */
            battery.setVisible(false);
            battery.getStyle().setMarginRight("0.4em");
            TableDataCell reading = new TableDataCell(battery, value);
            pad(reading);

            TableDataCell action = new TableDataCell(toggle);
            action.getStyle().setTextAlign(Style.TextAlign.RIGHT);
            action.getStyle().setWidth("2rem");
            pad(action);

            addCells(reading, action);
            setVisible(false);
        }

        private static void pad(com.vaadin.flow.component.Component cell) {
            cell.getElement().getStyle().setPadding("0.15rem 0");
        }

        ChartSeries series() {
            return series;
        }

        boolean isOnChart() {
            return onChart;
        }

        void show(SensorMeasurement sensor) {
            setVisible(series.of(sensor) != null);
            value.setText(series.value(sensor));
            if (series == ChartSeries.BATTERY) {
                sensor.battery().ifPresentOrElse(estimate -> {
                    battery.setVisible(true);
                    battery.setPercent(estimate.percent());
                }, () -> battery.setVisible(false));
            }
        }

        private void setOnChart(boolean on) {
            onChart = on;
            toggle.reflect(on);
            // The row is the legend: its name wears the line's colour while the line is on.
            name.getStyle().setColor(on ? series.cssColor() : "var(--vaadin-text-color-secondary)");
        }

        /**
         * The chart icon. Inline and small, so it sits on the row rather than
         * making it taller; coloured like the series while it is on, and in the
         * secondary text colour when it is not — a hint of a control, not a
         * button competing with the cog above.
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
                getStyle().setColor(on ? series.cssColor() : "var(--vaadin-text-color-secondary)");
            }
        }
    }
}
