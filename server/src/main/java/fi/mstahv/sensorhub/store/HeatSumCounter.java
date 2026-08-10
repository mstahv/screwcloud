package fi.mstahv.sensorhub.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A degree-day counter running on one sensor.
 *
 * <p>For hanging game: temperature multiplied by time, in
 * <i>vuorokausiastetta</i>, with forty as the usual guideline. A sensor can have
 * several — one carcass hung on Saturday and another on Wednesday are two counters
 * on the same thermometer — which is why this is a table of its own rather than
 * more columns on sensor_settings.
 *
 * <p>Not per browser, unlike the alert subscriptions: a counter is a fact about
 * what is hanging in the shed, and everyone looking at that sensor should see the
 * same one. Only the notifications are a personal choice, and those follow the
 * push subscriptions that already exist.
 */
@Entity
@Table(name = "heat_sum_counter",
        indexes = @Index(name = "idx_heat_sum_counter_sensor", columnList = "deviceId, sensorId"))
public class HeatSumCounter {

    /** Forty degree-days is the general guideline; some prefer sixty. */
    public static final double DEFAULT_TARGET = 40;

    public static final int MAX_COMMENT_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String deviceId;

    @Column(nullable = false, length = 8)
    private String sensorId;

    /** What is hanging: "hirvi", "kauris", a date, whatever the reader needs. */
    @Column(length = MAX_COMMENT_LENGTH)
    private String comment;

    @Column(nullable = false)
    private double target;

    /**
     * When the counter began. Everything the sensor recorded from here on counts,
     * which means a counter can be started for something already hanging by setting
     * this in the past.
     */
    @Column(nullable = false)
    private Instant startedAt;

    /** Notify when the target looks about a day away. */
    @Column(nullable = false)
    private boolean alertBeforeTarget;

    /** Notify when the target is reached. */
    @Column(nullable = false)
    private boolean alertAtTarget;

    /*
       Which notifications have already gone out. Persisted rather than kept in
       memory: a counter lives for days or weeks, and a server restart should not
       announce a target that was passed last Tuesday all over again.
    */
    @Column(nullable = false)
    private boolean notifiedBeforeTarget;

    @Column(nullable = false)
    private boolean notifiedAtTarget;

    /** JPA requires a default constructor. */
    protected HeatSumCounter() {
    }

    public HeatSumCounter(String deviceId, String sensorId, String comment, double target,
                          Instant startedAt) {
        this.deviceId = deviceId;
        this.sensorId = sensorId;
        this.comment = comment;
        this.target = target;
        this.startedAt = startedAt;
        // The defaults asked for: a day before, and at the target.
        this.alertBeforeTarget = true;
        this.alertAtTarget = true;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public double getTarget() {
        return target;
    }

    public void setTarget(double target) {
        this.target = target;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public boolean isAlertBeforeTarget() {
        return alertBeforeTarget;
    }

    public void setAlertBeforeTarget(boolean alertBeforeTarget) {
        this.alertBeforeTarget = alertBeforeTarget;
    }

    public boolean isAlertAtTarget() {
        return alertAtTarget;
    }

    public void setAlertAtTarget(boolean alertAtTarget) {
        this.alertAtTarget = alertAtTarget;
    }

    public boolean isNotifiedBeforeTarget() {
        return notifiedBeforeTarget;
    }

    public void setNotifiedBeforeTarget(boolean notifiedBeforeTarget) {
        this.notifiedBeforeTarget = notifiedBeforeTarget;
    }

    public boolean isNotifiedAtTarget() {
        return notifiedAtTarget;
    }

    public void setNotifiedAtTarget(boolean notifiedAtTarget) {
        this.notifiedAtTarget = notifiedAtTarget;
    }

    /** What to call it in a notification when no comment was given. */
    public String describe() {
        return comment == null || comment.isBlank() ? sensorId : comment;
    }
}
