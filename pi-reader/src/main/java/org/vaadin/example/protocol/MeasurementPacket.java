package org.vaadin.example.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the bytes a device sends.
 *
 * <p>Unlike the firmware, this refuses a packet with more sensors than the format
 * holds instead of dropping the last ones. On a microcontroller the check costs a
 * branch in a send path that cannot report anything anyway; here the caller can
 * choose which readings to leave out and say so in the log, and a silent drop
 * would look exactly like a tag that had gone quiet.
 *
 * <p>A sensor's record carries only the fields it has. A plain RuuviTag sends two
 * and costs 11 bytes; a Ruuvi Air sends four and costs 17. The size is therefore
 * computed rather than multiplied out, which is the one thing that changed here
 * when the format stopped being fixed width.
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

        List<List<Protocol.Field>> records = sensors.stream()
                .map(MeasurementPacket::fieldsOf)
                .toList();

        int size = Protocol.HEADER_SIZE + records.stream()
                .mapToInt(fields -> Protocol.SENSOR_HEADER_SIZE + fields.size() * Protocol.FIELD_SIZE)
                .sum();

        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) Protocol.VERSION);
        putId(buffer, deviceId);
        buffer.put((byte) sensors.size());
        buffer.putShort((short) sequence);

        for (int i = 0; i < sensors.size(); i++) {
            putId(buffer, sensors.get(i).id());
            List<Protocol.Field> fields = records.get(i);
            buffer.put((byte) fields.size());
            for (Protocol.Field field : fields) {
                buffer.put((byte) field.type());
                buffer.putShort((short) field.value());
            }
        }

        return buffer.array();
    }

    /**
     * The fields this sensor actually has, in registry order.
     *
     * <p>Order is not required by the format — a decoder reads types, not
     * positions — but a packet whose bytes are the same shape every time is a
     * packet somebody can read in a hex dump.
     */
    private static List<Protocol.Field> fieldsOf(SensorReading sensor) {
        List<Protocol.Field> fields = new ArrayList<>(Protocol.MAX_FIELDS);
        add(fields, Protocol.temperature(sensor.temperature()));
        add(fields, Protocol.humidity(sensor.humidity()));
        add(fields, Protocol.co2(sensor.co2()));
        add(fields, Protocol.pm25(sensor.pm25()));
        add(fields, Protocol.battery(sensor.batteryVoltage()));
        add(fields, Protocol.pressure(sensor.pressure()));
        add(fields, Protocol.voc(sensor.voc()));
        add(fields, Protocol.nox(sensor.nox()));
        add(fields, Protocol.luminosity(sensor.luminosity()));
        return fields;
    }

    private static void add(List<Protocol.Field> fields, Protocol.Field field) {
        if (field != null) {
            fields.add(field);
        }
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
