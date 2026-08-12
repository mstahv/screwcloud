package fi.mstahv.sensorhub.store;

import java.time.Instant;

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

/**
 * A device added by a browser: which devices one browser wants to see.
 *
 * <p>The browser is identified by a random token stored in WebStorage, not by
 * logging in. This is not a security mechanism: anyone who knows the token
 * reaches the same list, and a device's measurements are visible to anyone who
 * knows the device identifier. That is a deliberate choice here — the point is
 * only to remember a browser's choices without user accounts.
 *
 * <p>The row also carries whether this browser wants to be told when the device
 * stops reporting. That belongs here rather than in its own table: silence is a
 * property of a device, not of a sensor, and this is already the list of devices
 * one browser cares about.
 */
@Entity
@Table(name = "client_device",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_client_device",
                columnNames = {"clientId", "deviceId"}),
        indexes = @Index(name = "idx_client_device_client", columnList = "clientId"))
public class ClientDevice {

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
    @Column(nullable = false)
    private Instant addedAt;

    /**
     * Whether this browser wants a notification when the device stops reporting.
     * Defaults to false, so adding a device does not sign anyone up for anything.
     */
    @Column(nullable = false)
    private boolean alertOnSilence;

    /** JPA requires a default constructor. */
    protected ClientDevice() {
    }

    public ClientDevice(String clientId, String deviceId, Instant addedAt) {
        this.clientId = clientId;
        this.deviceId = deviceId;
        this.addedAt = addedAt;
    }

    public String getClientId() {
        return clientId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public boolean isAlertOnSilence() {
        return alertOnSilence;
    }

    public void setAlertOnSilence(boolean alertOnSilence) {
        this.alertOnSilence = alertOnSilence;
    }
}
