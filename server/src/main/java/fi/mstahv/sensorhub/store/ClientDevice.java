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

/**
 * A device added by a browser: which devices one browser wants to see.
 *
 * <p>The browser is identified by a random token stored in WebStorage, not by
 * logging in. This is not a security mechanism: anyone who knows the token
 * reaches the same list, and a device's measurements are visible to anyone who
 * knows the device identifier. That is a deliberate choice here — the point is
 * only to remember a browser's choices without user accounts.
 *
 * <p>This same table is the natural place to hang web push subscriptions on
 * later: they belong to a browser, just like this list does.
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

    @Column(nullable = false, length = 64)
    private String clientId;

    @Column(nullable = false, length = 8)
    private String deviceId;

    @Column(nullable = false)
    private Instant addedAt;

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
}
