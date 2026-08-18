package fi.mstahv.sensorhub.ui;

import in.virit.Gauge;
import in.virit.TemperatureGauge;
import in.virit.color.Color;
import in.virit.color.HexColor;
import in.virit.color.NamedColor;

import fi.mstahv.sensorhub.store.SensorThresholds;

/**
 * The temperature gauge on a sensor card, optionally coloured by the sensor's
 * configured alert / warning / OK bands.
 *
 * <p>Without configured bands it is a plain thermometer over the addon's stock
 * −40…+50 °C range, coloured as a cold-to-hot ramp. Configuring bands is therefore
 * purely additive: a sensor nobody has set limits for still shows its temperature
 * and says nothing about whether that temperature is good.
 *
 * <p>With bands configured the palette runs blue → green → red, so the colour
 * tells you which direction is wrong rather than merely that something is.
 */
class TemperatureBandGauge extends TemperatureGauge {
    /**
     * How far the displayed range extends beyond the alert limits. Enough that
     * the outer alert arcs are visibly arcs rather than slivers, while still
     * zooming the gauge onto the range that matters for this sensor.
     */
    private static final double RANGE_MARGIN = 5.0;

    void setThresholds(SensorThresholds thresholds) {
        if (!thresholds.isConfigured()) {
            applyStockConfiguration();
            return;
        }

        double min = thresholds.alertLow() - RANGE_MARGIN;
        double max = thresholds.alertHigh() + RANGE_MARGIN;
        setTemperatureRange(min, max);

        /*
           A sub-arc is defined by its upper limit, so the arcs are listed from
           cold to hot and each one runs from the previous limit up to its own.

           The cold side is blue and the warm side red, because the colour then
           says which direction is wrong — a red band at both ends would only say
           "bad" and leave the reader to work out which end they are looking at.
           Severity is carried by saturation on each side and by the position at
           the edge of the dial: light blue for a cold warning, deep blue for a
           cold alert.
        */
        setArc(new Gauge.GaugeArc().setSubArcs(
                new Gauge.GaugeSubArc(thresholds.alertLow(), NamedColor.ROYALBLUE)
                        .setTooltip("Alert: too cold"),
                new Gauge.GaugeSubArc(thresholds.okLow(), NamedColor.LIGHTSKYBLUE)
                        .setTooltip("Warning: cold"),
                new Gauge.GaugeSubArc(thresholds.okHigh(), NamedColor.GREEN)
                        .setTooltip("OK"),
                new Gauge.GaugeSubArc(thresholds.alertHigh(), NamedColor.ORANGE)
                        .setTooltip("Warning: warm"),
                new Gauge.GaugeSubArc(max, NamedColor.RED)
                        .setTooltip("Alert: too warm")));
    }

    /*
       A plain temperature ramp for a sensor nobody has set limits for: cold is
       blue, hot is red, and the two in between move between them.

       The range and the boundaries are the addon's own, so clearing the bands
       still reverts to the gauge everything else in this application assumes. The
       colours are not. The addon's defaults are green, yellow, orange and red —
       a traffic light, which is the wrong metaphor for a thermometer twice over.
       It says nothing about direction, since green means "cold" rather than
       "fine", and it made an ordinary living room glow red: the fill takes the
       colour of the band the value falls in, and everything above 20 °C is the
       last band. A comfortable 22 °C looked like an alarm.

       Muted on purpose. These sit on a card, in either colour scheme, next to
       text — a dial in full saturation would be the loudest thing on a page whose
       job is to be glanceable, and the bands here are information rather than a
       warning. Warnings are what configured thresholds are for, and those still
       use a green-to-red alert scale.
    */
    private static final Color COLD = HexColor.of("#4F7CC4");
    private static final Color COOL = HexColor.of("#7FAFD4");
    private static final Color WARM = HexColor.of("#D9A15B");
    private static final Color HOT = HexColor.of("#C4573C");

    private void applyStockConfiguration() {
        setTemperatureRange(-40, 50);
        setArc(new Gauge.GaugeArc().setSubArcs(
                new Gauge.GaugeSubArc(-20, COLD).setTooltip("Cold"),
                new Gauge.GaugeSubArc(0, COOL).setTooltip("Cool"),
                new Gauge.GaugeSubArc(20, WARM).setTooltip("Warm"),
                new Gauge.GaugeSubArc(50, HOT).setTooltip("Hot")));
    }

    /*
       There used to be an onAttach here that reached into the gauge's first child
       a hundred milliseconds after attach and painted it rgb(40, 44, 52), with a
       comment wondering whether the addon had a bug.

       Whatever it was working around, the answer is not a background: the card
       underneath is a surface already, and a hard grey rectangle on it read as
       something stuck to the card rather than as part of it. It also flashed,
       because it arrived a tenth of a second after everything else.

       The theme leaves the gauge transparent instead — see
       styles/sunset-glass.css, which keeps a rule for it because the addon sets
       that background inline as well.
    */
}
