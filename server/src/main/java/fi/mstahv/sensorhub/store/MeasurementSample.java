package fi.mstahv.sensorhub.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import fi.mstahv.sensorhub.validation.DeviceId;
import fi.mstahv.sensorhub.validation.SensorId;

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

    /*
       The constraints here are the only thing between the port and the database.
       A UDP packet is unauthenticated and can contain anything; the decoder checks
       the frame but not what the fields say, and it trims an identifier rather than
       judging it. A packet that fails any of these is refused at the insert, which
       the receiver logs as an invalid packet and carries on — see UdpReceiver.
    */
    @NotBlank
    @DeviceId
    @Column(nullable = false, length = 8)
    private String deviceId;

    @NotNull
    @SensorId
    @Column(nullable = false, length = 8)
    private String sensorId;

    /*
       Only the impossible is refused, not the improbable. A thermometer reporting
       -40 in a shed is a thermometer worth seeing; one reporting -3000 has not
       measured anything, and neither has one reporting negative humidity. No upper
       limits: a sensor in a sauna and a sensor that saturates above 100 %RH are both
       telling the truth as they see it, and a reading dropped here would take the
       rest of its packet with it.
    */
    @DecimalMin(value = "-273.15", message = "A temperature below absolute zero is not a measurement")
    private Double temperature;

    @DecimalMin(value = "0", message = "Humidity cannot be negative")
    private Double humidity;

    @NotNull
    @Column(nullable = false)
    private Instant receivedAt;

    /** The device's running packet number; the protocol carries it as uint16. */
    @Min(0)
    @Max(65535)
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
