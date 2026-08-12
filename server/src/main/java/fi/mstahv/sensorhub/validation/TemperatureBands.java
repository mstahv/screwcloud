package fi.mstahv.sensorhub.validation;

/**
 * The four temperature limits that divide a sensor's readings into bands, from
 * cold to hot:
 *
 * <pre>
 *   alert   warning        OK        warning   alert
 * ────────┬──────────┬─────────────┬──────────┬────────
 *      alertLow    okLow         okHigh   alertHigh
 * </pre>
 *
 * <p>An interface only so that one {@link IncreasingBands} validator can serve
 * both the stored {@code SensorThresholds} and the form that edits it. Those two
 * are not the same type — one is what the database holds, the other is what four
 * input fields currently contain, which may be half filled and in the wrong order.
 * The rule about how the four relate to each other is the same for both, and it is
 * a rule worth having in exactly one place.
 */
public interface TemperatureBands {

    /** Below this it is an alert; null when no bands are configured. */
    Double alertLow();

    /** Start of the OK band. */
    Double okLow();

    /** End of the OK band. */
    Double okHigh();

    /** Above this it is an alert. */
    Double alertHigh();
}
