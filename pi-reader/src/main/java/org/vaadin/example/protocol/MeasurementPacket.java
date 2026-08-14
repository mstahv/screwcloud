package org.vaadin.example.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the bytes a device sends.
 *
 * <p>Unlike the firmware, this refuses a packet with more sensors than the format
 * holds instead of dropping the last ones. On a microcontroller the check costs a
 * branch in a send path that cannot report anything anyway; here the caller can
 * choose which readings to leave out and say so in the log, and a silent drop
 * would look exactly like a tag that had gone quiet.
 */
public final class MeasurementPacket {

    private MeasurementPacket() {
    }

    /**
     * @param deviceId this device's identifier, 1 to {@value Protocol#ID_SIZE}
     *                 characters
     * @param sequence the packet counter, wrapping at 16 bits as the firmware's does
     * @param sensors  at most {@value Protocol#MAX_SENSORS} readings
     * @return the packet, ready to send
     */
    public static byte[] encode(String deviceId, int sequence, List<SensorReading> sensors) {
        if (sensors.size() > Protocol.MAX_SENSORS) {
            throw new IllegalArgumentException(
                    "A packet holds %d sensors, was given %d"
                            .formatted(Protocol.MAX_SENSORS, sensors.size()));
        }

        ByteBuffer buffer = ByteBuffer
                .allocate(Protocol.HEADER_SIZE + sensors.size() * Protocol.SENSOR_SIZE)
                .order(ByteOrder.BIG_ENDIAN);

        buffer.put((byte) Protocol.VERSION);
        putId(buffer, deviceId);
        buffer.put((byte) sensors.size());
        buffer.putShort((short) sequence);

        for (SensorReading sensor : sensors) {
            putId(buffer, sensor.id());
            buffer.putShort(Protocol.encodeTemperature(sensor.temperature()));
            buffer.putShort((short) Protocol.encodeHumidity(sensor.humidity()));
        }

        return buffer.array();
    }

    /**
     * A fixed width identifier, padded with spaces. The receiver trims them, so a
     * shorter identifier survives the trip unchanged.
     */
    private static void putId(ByteBuffer buffer, String id) {
        byte[] ascii = id.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < Protocol.ID_SIZE; i++) {
            buffer.put(i < ascii.length ? ascii[i] : (byte) ' ');
        }
    }
}
