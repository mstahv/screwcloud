package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.AttachEvent;
import in.virit.Gauge;
import in.virit.TemperatureGauge;
import in.virit.color.NamedColor;

import fi.mstahv.sensorhub.store.SensorThresholds;

/**
 * The temperature gauge on a sensor card, optionally coloured by the sensor's
 * configured alert / warning / OK bands.
 *
 * <p>Without configured bands this is a plain {@link TemperatureGauge} with its
 * stock −40…+50 °C range and colours. Configuring bands is therefore purely
 * additive: nothing changes for sensors nobody has set limits for.
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
       Restores what the TemperatureGauge constructor set up, so clearing the
       bands reverts the gauge immediately rather than only after the card is
       recreated.

       These values duplicate the addon's defaults because its
       setupTemperatureDefaults() is private and cannot be re-invoked. If the
       addon's defaults change, this drifts — but the alternative is a stale
       gauge after clearing, which is worse.
    */
    private void applyStockConfiguration() {
        setTemperatureRange(-40, 50);
        setArc(new Gauge.GaugeArc().setSubArcs(
                new Gauge.GaugeSubArc(-20, NamedColor.GREEN).setTooltip("Cold"),
                new Gauge.GaugeSubArc(0, NamedColor.YELLOW).setTooltip("Cool"),
                new Gauge.GaugeSubArc(20, NamedColor.ORANGE).setTooltip("Warm"),
                new Gauge.GaugeSubArc(50, NamedColor.RED).setTooltip("Hot")));
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // TODO bug in Gauge!?
        getElement().executeJs("var el = this; setTimeout(() => {el.firstChild.style.background = \"rgb(40 44 52)\";}, 100);");

    }
}
