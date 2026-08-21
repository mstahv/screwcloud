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
     * The token of the building drawing that stands for the device, null for
     * none. A token rather than a foreign anything: the drawings live with the
     * application, and an unknown token simply renders as no icon.
     */
    @Size(max = 16)
    @Column(length = 16)
    private String icon;

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

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }
}
