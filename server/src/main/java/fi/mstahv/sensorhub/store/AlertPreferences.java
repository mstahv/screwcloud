package fi.mstahv.sensorhub.store;

/**
 * Which alerts one browser wants from one sensor.
 *
 * @param onAlert the reading entered an alert band
 * @param onWarning the reading entered a warning band
 * @param onRecovery the reading came back into the OK band
 */
public record AlertPreferences(boolean onAlert, boolean onWarning, boolean onRecovery) {

    public static final AlertPreferences NONE = new AlertPreferences(false, false, false);

    /** True when nothing at all is subscribed to. */
    public boolean isSilent() {
        return !onAlert && !onWarning && !onRecovery;
    }

    /** Whether a transition into this zone is one of the subscribed events. */
    public boolean wants(TemperatureZone zone) {
        return switch (zone.severity()) {
            case ALERT -> onAlert;
            case WARNING -> onWarning;
            case OK -> onRecovery;
        };
    }
}
