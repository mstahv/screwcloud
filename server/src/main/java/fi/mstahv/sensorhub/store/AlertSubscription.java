package fi.mstahv.sensorhub.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.DeviceId;
import fi.mstahv.sensorhub.validation.SensorId;

/**
 * Which of one sensor's alerts a browser wants to be notified about.
 *
 * <p>Separate from {@link SensorSettings} on purpose, even though both describe a
 * sensor. The bands are a property of the sensor and shared by everyone looking
 * at the device; what someone wants their phone to buzz about is theirs alone. Two
 * people watching the same freezer can subscribe differently.
 *
 * <p>Also separate from {@link PushSubscription}: turning notifications off in the
 * browser removes the push subscription but leaves these choices intact, so
 * turning them back on does not mean configuring every sensor again.
 */
@Entity
@Table(name = "alert_subscription",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_alert_subscription",
                columnNames = {"clientId", "deviceId", "sensorId"}),
        indexes = @Index(name = "idx_alert_subscription_sensor",
                columnList = "deviceId, sensorId"))
public class AlertSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 64)
    @Column(nullable = false, length = 64)
    private String clientId;

    @NotBlank
    @DeviceId
    @Column(nullable = false, length = 8)
    private String deviceId;

    @NotNull
    @SensorId
    @Column(nullable = false, length = 8)
    private String sensorId;

    /** The reading entered an alert band: too cold or too hot. */
    @Column(nullable = false)
    private boolean onAlert;

    /** The reading entered a warning band, on either side of OK. */
    @Column(nullable = false)
    private boolean onWarning;

    /** The reading came back into the OK band. */
    @Column(nullable = false)
    private boolean onRecovery;

    /** JPA requires a default constructor. */
    protected AlertSubscription() {
    }

    public AlertSubscription(String clientId, String deviceId, String sensorId) {
        this.clientId = clientId;
        this.deviceId = deviceId;
        this.sensorId = sensorId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public boolean isOnAlert() {
        return onAlert;
    }

    public void setOnAlert(boolean onAlert) {
        this.onAlert = onAlert;
    }

    public boolean isOnWarning() {
        return onWarning;
    }

    public void setOnWarning(boolean onWarning) {
        this.onWarning = onWarning;
    }

    public boolean isOnRecovery() {
        return onRecovery;
    }

    public void setOnRecovery(boolean onRecovery) {
        this.onRecovery = onRecovery;
    }

    /** True when this row would notify about nothing, and can be deleted. */
    public boolean isSilent() {
        return !onAlert && !onWarning && !onRecovery;
    }
}
