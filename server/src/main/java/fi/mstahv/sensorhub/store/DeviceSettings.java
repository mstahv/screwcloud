package fi.mstahv.sensorhub.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.DeviceId;

/**
 * User-provided settings for one device, as {@link SensorSettings} is for one
 * sensor. One so far: the display name.
 *
 * <p>The name is null when the device has not been named, and the UI shows the
 * identifier then — same contract as a sensor's name.
 */
@Entity
@Table(name = "device_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_device_settings_device",
                columnNames = "deviceId"))
public class DeviceSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @DeviceId
    @Column(nullable = false, length = 8)
    private String deviceId;

    @Size(max = 64)
    @Column(length = 64)
    private String name;

    /**
     * Where the device's featured image loads from, null for none. For the
     * bundled paintings an application-relative path; for the user's own
     * picture whatever URL they gave — the application stores addresses, not
     * bytes, which is what keeps "no upload yet" honest.
     */
    @Size(max = 512)
    @Column(length = 512)
    private String imageUrl;

    /** JPA requires a default constructor. */
    protected DeviceSettings() {
    }

    public DeviceSettings(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
