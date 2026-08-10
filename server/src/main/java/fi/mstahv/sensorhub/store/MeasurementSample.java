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
 * One sensor reading at one point in time.
 *
 * <p>A packet is split into per-sensor rows, because a query is always of the
 * form "this sensor's values over this interval". The packet's sequence number
 * is kept on every row so readings from the same packet can be identified.
 *
 * <p>Temperature and humidity may be null: in the fixed size protocol a missing
 * value arrives as a sentinel, and that is a different thing from zero.
 */
@Entity
@Table(name = "measurement_sample", indexes = {
        @Index(name = "idx_sample_device_sensor_time", columnList = "deviceId, sensorId, receivedAt"),
        @Index(name = "idx_sample_device_time", columnList = "deviceId, receivedAt")
})
public class MeasurementSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String deviceId;

    @Column(nullable = false, length = 8)
    private String sensorId;

    private Double temperature;

    private Double humidity;

    @Column(nullable = false)
    private Instant receivedAt;

    private int sequence;

    /** JPA requires a default constructor. */
    protected MeasurementSample() {
    }

    public MeasurementSample(String deviceId, String sensorId, Double temperature, Double humidity,
                             Instant receivedAt, int sequence) {
        this.deviceId = deviceId;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.humidity = humidity;
        this.receivedAt = receivedAt;
        this.sequence = sequence;
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

    public Double getTemperature() {
        return temperature;
    }

    public Double getHumidity() {
        return humidity;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public int getSequence() {
        return sequence;
    }
}
