package fi.mstahv.sensorhub.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Both wire versions, because both are spoken. Version 1 is not a legacy path
 * kept warm out of politeness — every device flashed before the Ruuvi Air
 * arrived still sends it, and will until somebody walks up to it with a cable.
 */
class PacketDecoderTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void decodesLegacyHeaderAndSensors() {
        byte[] packet = legacyPacket("LAHT", 1234,
                legacySensor("DHT", 2560, 3570),
                legacySensor("RBF", 2493, 4077));

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
    void decodesLegacyNegativeTemperature() {
        byte[] packet = legacyPacket("LAHT", 1, legacySensor("ULK", -320, 8810));

        SensorMeasurement sensor = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertEquals(-3.20, sensor.temperature(), 0.0001);
        assertEquals(88.10, sensor.humidity(), 0.0001);
    }

    @Test
    void treatsLegacySentinelValuesAsMissing() {
        byte[] packet = legacyPacket("LAHT", 1, legacySensor("RBF", 0x8000, 0xFFFF));

        SensorMeasurement sensor = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertNull(sensor.temperature());
        assertNull(sensor.humidity());
    }

    @Test
    void rejectsUnknownVersion() {
        byte[] packet = legacyPacket("LAHT", 1, legacySensor("DHT", 2000, 5000));
        packet[0] = 99;

        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.decode(packet, packet.length, NOW));
    }

    @Test
    void rejectsLengthThatDoesNotMatchSensorCount() {
        byte[] packet = legacyPacket("LAHT", 1, legacySensor("DHT", 2000, 5000));

        // Otsake lupaa yhden anturin, mutta paketti katkaistaan kesken sen.
        assertThrows(IllegalArgumentException.class,
                () -> PacketDecoder.decode(packet, packet.length - 3, NOW));
    }

    @Test
    void rejectsPacketShorterThanHeader() {
        assertThrows(IllegalArgumentException.class, () -> PacketDecoder.decode(new byte[4], 4, NOW));
    }

    /* ---------------------------------------------------------------- version 2 */

    @Test
    void decodesTheFieldsASensorSent() {
        byte[] packet = packet("LAHT", 7,
                sensor("DHT", field(1, 2560), field(2, 3570)));

        SensorMeasurement dht = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertEquals("DHT", dht.sensorId());
        assertEquals(25.60, dht.temperature(), 0.0001);
        assertEquals(35.70, dht.humidity(), 0.0001);
        assertNull(dht.co2());
        assertNull(dht.pm25());
    }

    /**
     * The reason for version 2: a sensor that measures the air had nowhere to
     * put it in a record with two fixed slots.
     */
    @Test
    void decodesAirQuality() {
        byte[] packet = packet("LAHT", 1,
                sensor("RA1", field(1, 2150), field(2, 4200), field(4, 812), field(5, 63)));

        SensorMeasurement air = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertEquals(21.50, air.temperature(), 0.0001);
        assertEquals(42.00, air.humidity(), 0.0001);
        assertEquals(812.0, air.co2(), 0.0001);
        assertEquals(6.3, air.pm25(), 0.0001);
        assertTrue(air.hasAirQuality());
    }

    /**
     * A missing reading is an absent field rather than a sentinel, which is the
     * other half of what version 2 changed.
     */
    @Test
    void aFieldThatWasNotSentIsNull() {
        byte[] packet = packet("LAHT", 1, sensor("RBF", field(1, 2493)));

        SensorMeasurement sensor = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertEquals(24.93, sensor.temperature(), 0.0001);
        assertNull(sensor.humidity());
        assertFalse(sensor.hasAirQuality());
    }

    @Test
    void decodesNegativeTemperature() {
        byte[] packet = packet("LAHT", 1, sensor("ULK", field(1, -320)));

        SensorMeasurement sensor = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertEquals(-3.20, sensor.temperature(), 0.0001);
    }

    /**
     * The property the whole format rests on: a server older than the firmware
     * talking to it keeps every field it does understand.
     */
    @Test
    void stepsOverAFieldTypeItHasNeverHeardOf() {
        byte[] packet = packet("LAHT", 1,
                sensor("RA1", field(1, 2150), field(99, 1234), field(4, 812)));

        SensorMeasurement sensor = PacketDecoder.decode(packet, packet.length, NOW).sensors().get(0);

        assertEquals(21.50, sensor.temperature(), 0.0001);
        assertEquals(812.0, sensor.co2(), 0.0001);
    }

    @Test
    void sensorsCanCarryDifferentNumbersOfFields() {
        byte[] packet = packet("LAHT", 1,
                sensor("RBF", field(1, 2493), field(2, 4077)),
                sensor("RA1", field(1, 2150), field(4, 812), field(5, 63)),
                sensor("CPU", field(1, 3900)));

        DeviceMeasurement measurement = PacketDecoder.decode(packet, packet.length, NOW);

        assertEquals(3, measurement.sensors().size());
        assertNull(measurement.sensors().get(0).co2());
        assertEquals(812.0, measurement.sensors().get(1).co2(), 0.0001);
        assertNull(measurement.sensors().get(2).humidity());
    }

    @Test
    void rejectsAPacketThatEndsInsideASensorRecord() {
        byte[] packet = packet("LAHT", 1, sensor("RA1", field(1, 2150), field(4, 812)));

        assertThrows(IllegalArgumentException.class,
                () -> PacketDecoder.decode(packet, packet.length - 2, NOW));
    }

    @Test
    void rejectsTrailingBytesAfterTheLastSensor() {
        byte[] packet = packet("LAHT", 1, sensor("DHT", field(1, 2000)));
        byte[] withTail = java.util.Arrays.copyOf(packet, packet.length + 3);

        assertThrows(IllegalArgumentException.class,
                () -> PacketDecoder.decode(withTail, withTail.length, NOW));
    }

    @Test
    void rejectsAFieldCountBeyondTheRegistry() {
        byte[] packet = packet("LAHT", 1, sensor("DHT", field(1, 2000)));
        packet[8 + 4] = 99;  // the field count byte of the only sensor

        assertThrows(IllegalArgumentException.class,
                () -> PacketDecoder.decode(packet, packet.length, NOW));
    }

    /* ------------------------------------------------------------------ helpers */

    private static byte[] field(int type, int value) {
        return new byte[]{(byte) type, (byte) (value >> 8), (byte) value};
    }

    private static byte[] sensor(String id, byte[]... fields) {
        ByteBuffer buffer = ByteBuffer.allocate(5 + fields.length * 3).order(ByteOrder.BIG_ENDIAN);
        buffer.put(paddedId(id));
        buffer.put((byte) fields.length);
        for (byte[] field : fields) {
            buffer.put(field);
        }
        return buffer.array();
    }

    private static byte[] packet(String deviceId, int sequence, byte[]... sensors) {
        int body = java.util.Arrays.stream(sensors).mapToInt(sensor -> sensor.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(8 + body).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) PacketDecoder.VERSION);
        buffer.put(paddedId(deviceId));
        buffer.put((byte) sensors.length);
        buffer.putShort((short) sequence);
        for (byte[] sensor : sensors) {
            buffer.put(sensor);
        }
        return buffer.array();
    }

    private static byte[] legacySensor(String id, int rawTemperature, int rawHumidity) {
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buffer.put(paddedId(id));
        buffer.putShort((short) rawTemperature);
        buffer.putShort((short) rawHumidity);
        return buffer.array();
    }

    private static byte[] legacyPacket(String deviceId, int sequence, byte[]... sensors) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + sensors.length * 8).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) PacketDecoder.VERSION_LEGACY);
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
