package fi.mstahv.sensorhub.protocol;

import java.util.Optional;

/**
 * What is left in a RuuviTag's coin cell, guessed from its voltage.
 *
 * <p>A guess, and said so wherever it is shown. A lithium manganese dioxide cell
 * — the CR2477 every tag ships with — does not run down in a straight line. It
 * starts a little over 3.0 V, settles at 3.0 within hours, spends months sliding
 * to about 2.7 V, and then falls off a cliff: from there to the 1.7 V where the
 * radio's processor browns out is a matter of weeks, and the tag starts
 * rebooting on momentary droops before it goes quiet. Ruuvi's own firmware
 * notes describe exactly this shape and conclude that predicting the end from
 * the voltage is unreliable, which is why their app shows a voltage and a
 * "low" word and no percentage.
 *
 * <p>So this is not a fuel gauge. It is the discharge curve above turned into a
 * number a reader can glance at — 95 means "new", 50 means "past the middle of
 * the plateau", 10 means "buy a battery this month" — rounded to fives so that
 * it does not pretend to a precision it does not have.
 *
 * <h2>The anchors</h2>
 *
 * <table>
 * <tr><th>volts</th><th>left</th><th>why there</th></tr>
 * <tr><td>3.00</td><td>100 %</td><td>a new cell under load, per Ruuvi</td></tr>
 * <tr><td>2.90</td><td>80 %</td><td>the plateau's slow slide</td></tr>
 * <tr><td>2.80</td><td>55 %</td><td></td></tr>
 * <tr><td>2.70</td><td>25 %</td><td>the end of the plateau, where Ruuvi's curve turns down</td></tr>
 * <tr><td>2.50</td><td>5 %</td><td>Ruuvi's "replace the battery" voltage at room temperature</td></tr>
 * <tr><td>2.00</td><td>0 %</td><td>the tag is rebooting on droops by now</td></tr>
 * </table>
 *
 * <h2>Cold</h2>
 *
 * <p>A cold cell reads low without being empty: the voltage sags in the frost
 * and comes back at noon. Ruuvi's app moves its "low" line with the
 * temperature — 2.5 V above freezing, 2.3 V down to −20 °C, 2.0 V below that —
 * and this does the same, by reading the voltage as if it were that much higher
 * before looking it up. A tag in a freezer at 2.4 V is therefore "about 80 %",
 * not "low", which is roughly what its owner would find on bringing it indoors.
 *
 * @param volts what the tag reported
 * @param percent the guess, in steps of five
 * @param low below Ruuvi's replace-it line for the tag's temperature
 */
public record BatteryEstimate(double volts, int percent, boolean low) {

    /*
       Ruuvi Station's thresholds, from its strings.xml and the support page
       "Battery information and changing the RuuviTag battery": the voltage at
       which the app says "Low battery", by the tag's own temperature.
    */
    static final double LOW_ABOVE_FREEZING = 2.5;
    static final double LOW_BELOW_FREEZING = 2.3;
    static final double LOW_BELOW_MINUS_TWENTY = 2.0;

    /** The curve, as (volts, percent) pairs from full to empty. */
    private static final double[][] CURVE = {
            {3.00, 100}, {2.90, 80}, {2.80, 55}, {2.70, 25}, {2.50, 5}, {2.00, 0}
    };

    /**
     * @param volts the reported voltage, or null when the sensor has none
     * @param temperature the tag's own temperature, which decides how much of a
     *        low reading is the cold talking; null is read as room temperature
     */
    public static Optional<BatteryEstimate> of(Double volts, Double temperature) {
        if (volts == null) {
            return Optional.empty();
        }
        double threshold = lowThreshold(temperature);
        /*
           The cold correction: the amount by which the threshold has been
           lowered for this temperature is the amount the cold is believed to be
           hiding, so the voltage is read that much higher against the curve.
        */
        double asIfWarm = volts + (LOW_ABOVE_FREEZING - threshold);
        return Optional.of(new BatteryEstimate(volts, percent(asIfWarm), volts < threshold));
    }

    static double lowThreshold(Double temperature) {
        if (temperature == null) {
            return LOW_ABOVE_FREEZING;
        }
        if (temperature < -20) {
            return LOW_BELOW_MINUS_TWENTY;
        }
        if (temperature < 0) {
            return LOW_BELOW_FREEZING;
        }
        return LOW_ABOVE_FREEZING;
    }

    /** Linear between the anchors, clamped at the ends, rounded to fives. */
    static int percent(double volts) {
        if (volts >= CURVE[0][0]) {
            return 100;
        }
        for (int i = 1; i < CURVE.length; i++) {
            double[] upper = CURVE[i - 1];
            double[] lower = CURVE[i];
            if (volts >= lower[0]) {
                double fraction = (volts - lower[0]) / (upper[0] - lower[0]);
                double exact = lower[1] + fraction * (upper[1] - lower[1]);
                return (int) (Math.round(exact / 5.0) * 5);
            }
        }
        return 0;
    }
}
