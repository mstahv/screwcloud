package fi.mstahv.sensorhub.store;

/**
 * Which of a sensor's five configured bands a reading falls in.
 *
 * <p>The labels are the same strings the gauge uses for its sub-arc tooltips, so
 * a notification says what the dial says.
 *
 * <p>The direction matters as much as the severity: "too cold" and "too hot" are
 * different problems with different remedies, and a notification that only said
 * "alert" would leave the reader to open the app to find out which.
 */
public enum TemperatureZone {

    ALERT_LOW(Severity.ALERT, "Alert: too cold"),
    WARNING_LOW(Severity.WARNING, "Warning: cold"),
    OK(Severity.OK, "Back to normal"),
    WARNING_HIGH(Severity.WARNING, "Warning: warm"),
    ALERT_HIGH(Severity.ALERT, "Alert: too warm");

    /**
     * How bad a zone is, without regard to direction. Notifications are about
     * changes in severity: staying inside the same zone, or drifting from one
     * warning to the other side's warning, is not news worth a phone buzzing.
     */
    public enum Severity {
        OK, WARNING, ALERT
    }

    private final Severity severity;
    private final String label;

    TemperatureZone(Severity severity, String label) {
        this.severity = severity;
        this.label = label;
    }

    public Severity severity() {
        return severity;
    }

    public String label() {
        return label;
    }
}
