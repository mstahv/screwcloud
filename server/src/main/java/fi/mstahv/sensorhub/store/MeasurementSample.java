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
 * <p>Every reading may be null, and null is a different thing from zero: it
 * means the sensor did not send that value. Most sensors send two of the four —
 * the air columns belong to a Ruuvi Air and are empty for every plain tag, which
 * is the same shape this table already had for the humidity a RuuviTag Pro 2in1
 * never reports.
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

    /*
       Air quality, from a Ruuvi Air. Negative is impossible for both — there is
       no such thing as less than no carbon dioxide — and as with the two above,
       no upper limit: a room that has been shut for a week and a reading taken
       beside a fire are both the sensor telling the truth as it sees it.
    */
    @DecimalMin(value = "0", message = "A CO2 concentration cannot be negative")
    private Double co2;

    @DecimalMin(value = "0", message = "A particulate concentration cannot be negative")
    private Double pm25;

    /*
       The sensor's own battery, in volts. Only tags report one; the column is
       null for everything else, like the air fields above. Kept as a plain reading
       rather than derived into a "low" flag, so that the threshold can move
       without rewriting history.
    */
    @DecimalMin(value = "0", message = "A battery voltage cannot be negative")
    private Double batteryVoltage;

    /*
       The rest of what a Ruuvi measures, added when the protocol's reserved
       types were put to use: pressure from every tag and Air, and the Air's two
       indexes and its light. Nullable like everything above the temperature.
    */
    @DecimalMin(value = "0", message = "A pressure cannot be negative")
    private Double pressure;

    @DecimalMin(value = "0", message = "A VOC index cannot be negative")
    private Double voc;

    @DecimalMin(value = "0", message = "A NOx index cannot be negative")
    private Double nox;

    @DecimalMin(value = "0", message = "A luminosity cannot be negative")
    private Double luminosity;

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

    /** A sensor that measures only temperature and humidity, which is most of them. */
    public MeasurementSample(String deviceId, String sensorId, Double temperature, Double humidity,
                             Instant receivedAt, int sequence) {
        this(deviceId, sensorId, temperature, humidity, null, null, receivedAt, sequence);
    }

    public MeasurementSample(String deviceId, String sensorId, Double temperature, Double humidity,
                             Double co2, Double pm25, Instant receivedAt, int sequence) {
        this(deviceId, sensorId, temperature, humidity, co2, pm25, null, receivedAt, sequence);
    }

    public MeasurementSample(String deviceId, String sensorId, Double temperature, Double humidity,
                             Double co2, Double pm25, Double batteryVoltage,
                             Instant receivedAt, int sequence) {
        this(deviceId, sensorId, temperature, humidity, co2, pm25, batteryVoltage,
                null, null, null, null, receivedAt, sequence);
    }

    public MeasurementSample(String deviceId, String sensorId, Double temperature, Double humidity,
                             Double co2, Double pm25, Double batteryVoltage,
                             Double pressure, Double voc, Double nox, Double luminosity,
                             Instant receivedAt, int sequence) {
        this.pressure = pressure;
        this.voc = voc;
        this.nox = nox;
        this.luminosity = luminosity;
        this.deviceId = deviceId;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.humidity = humidity;
        this.co2 = co2;
        this.pm25 = pm25;
        this.batteryVoltage = batteryVoltage;
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

    public Double getCo2() {
        return co2;
    }

    public Double getPm25() {
        return pm25;
    }

    public Double getBatteryVoltage() {
        return batteryVoltage;
    }

    public Double getPressure() {
        return pressure;
    }

    public Double getVoc() {
        return voc;
    }

    public Double getNox() {
        return nox;
    }

    public Double getLuminosity() {
        return luminosity;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public int getSequence() {
        return sequence;
    }
}
