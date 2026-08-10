package fi.mstahv.sensorhub.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the binary packet sent by a device.
 *
 * <p>The format is fixed size and big endian. The header is 8 bytes and each
 * sensor another 8:
 *
 * <pre>
 * Header                          Sensor (repeated count times)
 * 0     version   uint8 = 1       0..3  id           4 x ASCII
 * 1..4  deviceId  4 x ASCII       4..5  temperature  int16, 0.01 °C
 * 5     count     uint8           6..7  humidity     uint16, 0.01 %RH
 * 6..7  sequence  uint16
 * </pre>
 *
 * <p>Missing values are marked with the sentinels {@code 0x8000} and
 * {@code 0xFFFF}, because a fixed size format cannot omit a field. They become
 * nulls here.
 *
 * <p>The packet is unauthenticated. A UDP sender address is trivial to spoof, so
 * the server must not trust the data for anything beyond displaying it. If this
 * ever gets another use, the format needs something like an HMAC and a version
 * bump.
 */
public final class PacketDecoder {

    public static final int VERSION = 1;

    private static final int HEADER_SIZE = 8;
    private static final int SENSOR_SIZE = 8;
    private static final int ID_SIZE = 4;
    private static final int MAX_SENSORS = 8;

    private static final short TEMPERATURE_INVALID = (short) 0x8000;
    private static final int HUMIDITY_INVALID = 0xFFFF;

    private PacketDecoder() {
    }

    /**
     * @throws IllegalArgumentException if the packet is not in a recognised form
     */
    public static DeviceMeasurement decode(byte[] data, int length, Instant receivedAt) {
        if (length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Packet too short: " + length + " bytes, the header needs " + HEADER_SIZE);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);

        int version = Byte.toUnsignedInt(buffer.get());
        if (version != VERSION) {
            throw new IllegalArgumentException("Unknown protocol version " + version);
        }

        String deviceId = readId(buffer);
        int count = Byte.toUnsignedInt(buffer.get());
        int sequence = Short.toUnsignedInt(buffer.getShort());

        if (count > MAX_SENSORS) {
            throw new IllegalArgumentException("Sensor count " + count + " exceeds the limit " + MAX_SENSORS);
        }
        int expected = HEADER_SIZE + count * SENSOR_SIZE;
        if (length != expected) {
            throw new IllegalArgumentException(
                    "Length " + length + " does not match sensor count " + count + " (expected " + expected + ")");
        }

        List<SensorMeasurement> sensors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            sensors.add(new SensorMeasurement(
                    readId(buffer),
                    decodeTemperature(buffer.getShort()),
                    decodeHumidity(Short.toUnsignedInt(buffer.getShort()))));
        }

        return new DeviceMeasurement(deviceId, sequence, receivedAt, List.copyOf(sensors));
    }

    private static String readId(ByteBuffer buffer) {
        byte[] raw = new byte[ID_SIZE];
        buffer.get(raw);
        return new String(raw, StandardCharsets.US_ASCII).trim();
    }

    private static Double decodeTemperature(short raw) {
        return raw == TEMPERATURE_INVALID ? null : raw / 100.0;
    }

    private static Double decodeHumidity(int raw) {
        return raw == HUMIDITY_INVALID ? null : raw / 100.0;
    }
}
