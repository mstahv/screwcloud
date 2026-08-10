package fi.mstahv.sensorhub.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * User-provided settings for one sensor.
 *
 * <p>The key is device and sensor together, because sensor identifiers are only
 * unique within a device — two devices can each have a sensor called
 * {@code DHT}.
 *
 * <p>A running id plus a unique constraint is used instead of a composite key,
 * which saves the {@code @IdClass} ceremony. The name is null when the sensor
 * has not been named, and the temperature limits are null when no bands have
 * been configured.
 */
@Entity
@Table(name = "sensor_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sensor_settings_device_sensor",
                columnNames = {"deviceId", "sensorId"}))
public class SensorSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String deviceId;

    @Column(nullable = false, length = 8)
    private String sensorId;

    @Column(length = 64)
    private String name;

    /*
       Temperature bands for the gauge. Either all four are set or none are;
       SensorThresholds.validate() enforces that before saving.
    */
    private Double alertLow;
    private Double okLow;
    private Double okHigh;
    private Double alertHigh;

    /** JPA requires a default constructor. */
    protected SensorSettings() {
    }

    public SensorSettings(String deviceId, String sensorId) {
        this.deviceId = deviceId;
        this.sensorId = sensorId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SensorThresholds getThresholds() {
        return new SensorThresholds(alertLow, okLow, okHigh, alertHigh);
    }

    public void setThresholds(SensorThresholds thresholds) {
        this.alertLow = thresholds.alertLow();
        this.okLow = thresholds.okLow();
        this.okHigh = thresholds.okHigh();
        this.alertHigh = thresholds.alertHigh();
    }
}
