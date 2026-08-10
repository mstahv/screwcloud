package fi.mstahv.sensorhub.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class PacketDecoderTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void decodesHeaderAndSensors() {
        byte[] packet = packet("LAHT", 1234,
                sensor("DHT", 2560, 3570),
                sensor("RBF", 2493, 4077));

        DeviceMeasurement measurement = PacketDecoder.decode(packet, packet.length, NOW);

        assertEquals("LAHT", measurement.deviceId());
        assertEquals(1234, measurement.sequence());
        assertEquals(NOW, measurement.receivedAt());
        assertEquals(2, measurement.sensors().size());

        SensorMeasurement dht = measurement.sensors().get(0);
        assertEquals("DHT", dht.sensorId());
        assertEquals(25.60, dht.temperature(), 0.0001);
        assertEquals(35.70, dht.humidity(), 0.0001);

        SensorMeasurement ruuvi = measurement.sensors().get(1);
        assertEquals("RBF", ruuvi.sensorId());
        assertEquals(24.93, ruuvi.temperature(), 0.0001);
    }

    @Test
    void decodesNegativeTemperature() {
        byte[] packet = packet("LAHT", 1, sensor("ULK", -320, 8810));

        SensorMeasurement sensor = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertEquals(-3.20, sensor.temperature(), 0.0001);
        assertEquals(88.10, sensor.humidity(), 0.0001);
    }

    @Test
    void treatsSentinelValuesAsMissing() {
        byte[] packet = packet("LAHT", 1, sensor("RBF", 0x8000, 0xFFFF));

        SensorMeasurement sensor = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertNull(sensor.temperature());
        assertNull(sensor.humidity());
    }

    @Test
    void rejectsUnknownVersion() {
        byte[] packet = packet("LAHT", 1, sensor("DHT", 2000, 5000));
        packet[0] = 99;

        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.decode(packet, packet.length, NOW));
    }

    @Test
    void rejectsLengthThatDoesNotMatchSensorCount() {
        byte[] packet = packet("LAHT", 1, sensor("DHT", 2000, 5000));

        // Otsake lupaa yhden anturin, mutta paketti katkaistaan kesken sen.
        assertThrows(IllegalArgumentException.class,
                () -> PacketDecoder.decode(packet, packet.length - 3, NOW));
    }

    @Test
    void rejectsPacketShorterThanHeader() {
        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.decode(new byte[4], 4, NOW));
    }

    private static byte[] sensor(String id, int rawTemperature, int rawHumidity) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buffer.put(paddedId(id));
        buffer.putShort((short) rawTemperature);
        buffer.putShort((short) rawHumidity);
        return buffer.array();
    }

    private static byte[] packet(String deviceId, int sequence, byte[]... sensors) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + sensors.length * 8).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) PacketDecoder.VERSION);
        buffer.put(paddedId(deviceId));
        buffer.put((byte) sensors.length);
        buffer.putShort((short) sequence);
        for (byte[] sensor : sensors) {
            buffer.put(sensor);
        }
        return buffer.array();
    }

    private static byte[] paddedId(String id) {
        byte[] padded = "    ".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, padded.length));
        return padded;
    }
}
