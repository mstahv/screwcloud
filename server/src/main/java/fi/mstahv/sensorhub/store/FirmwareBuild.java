package fi.mstahv.sensorhub.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import fi.mstahv.sensorhub.validation.DeviceId;

/**
 * A device identifier that firmware has been built for.
 *
 * <p>Its only job is to make "is this identifier free" answerable before the
 * device has ever spoken. The measurements know an identifier once a packet has
 * arrived under it; this knows one from the moment somebody built for it, which
 * is the several hours in between where two people would otherwise be handed the
 * same one.
 *
 * <p>It deliberately holds nothing about the build. Not the network, not the
 * password, not who asked — none of that is anybody's business afterwards, and a
 * row that does not have it cannot leak it.
 */
@Entity
@Table(name = "firmware_build",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_firmware_build_device", columnNames = "deviceId"))
public class FirmwareBuild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @DeviceId
    @Column(nullable = false, length = 8)
    private String deviceId;

    @NotNull
    @Column(nullable = false)
    private Instant builtAt;

    /** JPA requires a default constructor. */
    protected FirmwareBuild() {
    }

    public FirmwareBuild(String deviceId, Instant builtAt) {
        this.deviceId = deviceId;
        this.builtAt = builtAt;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Instant getBuiltAt() {
        return builtAt;
    }

    public void setBuiltAt(Instant builtAt) {
        this.builtAt = builtAt;
    }
}
