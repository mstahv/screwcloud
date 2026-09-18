package fi.mstahv.sensorhub.ui;

import java.util.function.Function;

import in.virit.color.Color;

import fi.mstahv.sensorhub.protocol.BatteryLevel;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.store.HistoryPoint;

/**
 * The readings a sensor card shows under its curve, and can lay over it.
 *
 * <p>Each is a line of text on the card — its own line, because "801 ppm CO2 ·
 * 0.5 ug/m3 PM2.5" read as one measurement with a strange unit — and each can be
 * switched onto the temperature curve as a second line in its own colour. The
 * colour is the same in the text when the line is on, so the row is the legend.
 *
 * <p>The colours are fixed rather than taken from the theme, and chosen to read
 * on both the pale and the dark card: a series is identified by its colour, and
 * a colour that changed with the scheme would identify nothing.
 */
enum ChartSeries {

    HUMIDITY("humidity", "#4aa3df", SensorMeasurement::humidity, HistoryPoint::humidity) {
        @Override
        String text(SensorMeasurement sensor) {
            return Readings.format(sensor.humidity(), "%.1f %% RH");
        }
    },

    /*
       CO₂ without decimals because the sensor's own accuracy is tens of ppm, and
       particulates with one because the numbers that matter are small.
    */
    CO2("CO2", "#e0862b", SensorMeasurement::co2, HistoryPoint::co2) {
        @Override
        String text(SensorMeasurement sensor) {
            return Readings.format(sensor.co2(), "%.0f ppm CO2");
        }
    },

    PM25("PM2.5", "#9b6bd6", SensorMeasurement::pm25, HistoryPoint::pm25) {
        @Override
        String text(SensorMeasurement sensor) {
            return Readings.format(sensor.pm25(), "%.1f ug/m3 PM2.5");
        }
    },

    /**
     * Two decimals: a coin cell spends months between 3.0 and 2.9, and the second
     * decimal is what shows it moving at all. Beside it the guess at what is left,
     * said as "about" because that is what it is — see {@link BatteryLevel} — and
     * in place of the guess, once the cell is under Ruuvi's replace-it line for
     * this temperature, the word that matters. A word rather than a colour,
     * because the gauge above already uses colour to mean something about the
     * temperature.
     */
    BATTERY("battery", "#3cb371", SensorMeasurement::batteryVoltage, HistoryPoint::batteryVoltage) {
        @Override
        String text(SensorMeasurement sensor) {
            String volts = "Battery " + Readings.format(sensor.batteryVoltage(), "%.2f V");
            return sensor.battery()
                    .map(level -> level.low()
                            ? volts + " · low, replace it soon"
                            : volts + " · about %d %% left".formatted(level.percent()))
                    .orElse(volts);
        }
    };

    private final String caption;
    private final String cssColor;
    private final Function<SensorMeasurement, Double> current;
    private final Function<HistoryPoint, Double> past;

    ChartSeries(String caption, String cssColor, Function<SensorMeasurement, Double> current,
                Function<HistoryPoint, Double> past) {
        this.caption = caption;
        this.cssColor = cssColor;
        this.current = current;
        this.past = past;
    }

    /** The line of text for the latest reading, with its unit. */
    abstract String text(SensorMeasurement sensor);

    /** What it is called in an accessible name: "Show humidity on the chart". */
    String caption() {
        return caption;
    }

    /** The colour of its line on the chart, and of its text while the line is on. */
    String cssColor() {
        return cssColor;
    }

    Color color() {
        return Color.parseCssColor(cssColor);
    }

    /** The latest value, or null when the sensor has none. */
    Double of(SensorMeasurement sensor) {
        return current.apply(sensor);
    }

    /** The value at one point of the history, or null. */
    Double of(HistoryPoint point) {
        return past.apply(point);
    }
}
