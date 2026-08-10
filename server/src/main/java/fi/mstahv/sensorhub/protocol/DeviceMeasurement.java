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
}
