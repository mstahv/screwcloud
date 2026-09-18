package fi.mstahv.sensorhub.ui;

import java.util.function.Function;

import in.virit.color.Color;

import fi.mstahv.sensorhub.protocol.BatteryEstimate;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.store.HistoryPoint;

/**
 * The readings a sensor card shows under its curve, and can lay over it.
 *
 * <p>Each is a row of the card's {@link ReadingsTable} — a name and a value —
 * and each can be switched onto the temperature curve as a second line in its
 * own colour. The colour is the same in the name when the line is on, so the row
 * is the legend.
 *
 * <p>The colours are fixed rather than taken from the theme, and chosen to read
 * on both the pale and the dark card: a series is identified by its colour, and
 * a colour that changed with the scheme would identify nothing.
 */
enum ChartSeries {

    HUMIDITY("Humidity", "humidity", "#4aa3df", SensorMeasurement::humidity, HistoryPoint::humidity) {
        @Override
        String value(SensorMeasurement sensor) {
            return Readings.format(sensor.humidity(), "%.1f %% RH");
        }
    },

    /*
       Two decimals, as Ruuvi shows it: a barometer's day is a few hectopascals,
       and one decimal made a still afternoon look like a flat line.
    */
    PRESSURE("Pressure", "pressure", "#6c8ebf", SensorMeasurement::pressure, HistoryPoint::pressure) {
        @Override
        String value(SensorMeasurement sensor) {
            return Readings.format(sensor.pressure(), "%.2f hPa");
        }
    },

    /*
       CO₂ without decimals because the sensor's own accuracy is tens of ppm, and
       particulates with one because the numbers that matter are small.
    */
    CO2("CO₂", "CO2", "#e0862b", SensorMeasurement::co2, HistoryPoint::co2) {
        @Override
        String value(SensorMeasurement sensor) {
            return Readings.format(sensor.co2(), "%.0f ppm");
        }
    },

    PM25("PM2.5", "PM2.5", "#9b6bd6", SensorMeasurement::pm25, HistoryPoint::pm25) {
        @Override
        String value(SensorMeasurement sensor) {
            return Readings.format(sensor.pm25(), "%.1f µg/m³");
        }
    },

    /*
       Ruuvi's indexes, shown as Ruuvi shows them: a bare number on a 0–500 scale
       where 100 is the sensor's idea of typical air and more is worse. Not a
       concentration — the sensor learns the room and rates it against itself —
       so no unit, and no decimals either.
    */
    VOC("VOC index", "the VOC index", "#8f9a27", SensorMeasurement::voc, HistoryPoint::voc) {
        @Override
        String value(SensorMeasurement sensor) {
            return Readings.format(sensor.voc(), "%.0f");
        }
    },

    NOX("NOx index", "the NOx index", "#b5533c", SensorMeasurement::nox, HistoryPoint::nox) {
        @Override
        String value(SensorMeasurement sensor) {
            return Readings.format(sensor.nox(), "%.0f");
        }
    },

    /*
       Whole lux: the device sends them on a logarithmic byte, so the number is
       coarse to begin with — good for a lit room against a dark one, not for
       comparing two lamps.
    */
    LUMINOSITY("Light", "the light", "#f2b134", SensorMeasurement::luminosity, HistoryPoint::luminosity) {
        @Override
        String value(SensorMeasurement sensor) {
            return Readings.format(sensor.luminosity(), "%.0f lx");
        }
    },

    /**
     * Two decimals: a coin cell spends months between 3.0 and 2.9, and the second
     * decimal is what shows it moving at all. Beside it the guess at what is left,
     * said as "about" because that is what it is — see {@link BatteryEstimate} — and
     * in place of the guess, once the cell is under Ruuvi's replace-it line for
     * this temperature, the word that matters. A word rather than a colour,
     * because the gauge above already uses colour to mean something about the
     * temperature.
     */
    BATTERY("Battery", "battery", "#3cb371", SensorMeasurement::batteryVoltage,
            HistoryPoint::batteryVoltage) {
        @Override
        String value(SensorMeasurement sensor) {
            String volts = Readings.format(sensor.batteryVoltage(), "%.2f V");
            return sensor.battery()
                    .map(level -> level.low()
                            ? volts + " · low, replace it soon"
                            : volts + " · about %d %%".formatted(level.percent()))
                    .orElse(volts);
        }
    };

    private final String label;
    private final String caption;
    private final String cssColor;
    private final Function<SensorMeasurement, Double> current;
    private final Function<HistoryPoint, Double> past;

    ChartSeries(String label, String caption, String cssColor,
                Function<SensorMeasurement, Double> current, Function<HistoryPoint, Double> past) {
        this.label = label;
        this.caption = caption;
        this.cssColor = cssColor;
        this.current = current;
        this.past = past;
    }

    /** The latest reading with its unit, for the value column. */
    abstract String value(SensorMeasurement sensor);

    /** The row's name, for the first column. */
    String label() {
        return label;
    }

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
