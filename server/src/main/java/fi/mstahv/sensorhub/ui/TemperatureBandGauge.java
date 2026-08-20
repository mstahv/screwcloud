package fi.mstahv.sensorhub.ui;

import in.virit.Gauge;
import in.virit.TemperatureGauge;
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
       The stock dial for a sensor nobody has set limits for. This used to be
       forty lines of duplicated configuration with a comment warning that it
       would drift — the cold-to-hot ramp lived here because the addon's own
       defaults were a traffic light and its setup method was private. Both
       halves have been fixed upstream: the addon's stock dial is this
       application's muted blue-to-red ramp now, and resetToDefaults() is the
       public way back to it.
    */
    private void applyStockConfiguration() {
        resetToDefaults();
    }
}
