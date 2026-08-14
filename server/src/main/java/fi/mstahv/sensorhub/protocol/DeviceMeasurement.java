package fi.mstahv.sensorhub.protocol;

import java.time.Instant;
import java.util.List;

/**
 * One packet from a device, decoded.
 *
 * @param deviceId device identifier, at most 4 characters
 * @param sequence the device's running packet number, wraps after 65535
 * @param receivedAt arrival time on the server; the devices have no clock
 * @param sensors the sensor readings
 */
public record DeviceMeasurement(String deviceId, int sequence, Instant receivedAt,
                                List<SensorMeasurement> sensors) {

    /**
     * Whether this device reports nothing but its own chip temperature.
     *
     * <p>The chip is normally a diagnostic about the box rather than a measuring
     * point: it is shown on the device's status line, left off the cards, and not
     * counted as a sensor. A board that measures nothing else is the exception the
     * whole rule needs. Its one reading is what it is for, and hiding it would
     * leave a device with a status line and an empty page.
     *
     * <p>Here rather than in a view because three of them ask the same question,
     * and a rule about what a reading means belongs with the reading.
     */
    public boolean measuresOnlyItself() {
        return !sensors.isEmpty() && sensors.stream().allMatch(SensorMeasurement::isDeviceInternal);
    }

    /**
     * The sensors that stand for a place being measured: everything but the chip,
     * or the chip alone when that is all this device has.
     */
    public List<SensorMeasurement> measuringPoints() {
        if (measuresOnlyItself()) {
            return sensors;
        }
        return sensors.stream().filter(sensor -> !sensor.isDeviceInternal()).toList();
    }
}
