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
 * <p>Big endian, and the header is the same 8 bytes in both versions:
 *
 * <pre>
 * 0     version   uint8, 1 or 2
 * 1..4  deviceId  4 x ASCII
 * 5     count     uint8
 * 6..7  sequence  uint16
 * </pre>
 *
 * <h2>Two versions, and both stay</h2>
 *
 * <p><b>Version 1</b> is a fixed 8-byte sensor record — id, temperature,
 * humidity — with {@code 0x8000} and {@code 0xFFFF} standing in for a value the
 * sensor did not give, because a fixed record cannot leave anything out.
 *
 * <p><b>Version 2</b> is type-length-value: {@code id(4) + fieldCount(1)} and
 * then three bytes per field, a type and a 16-bit value. A missing reading is
 * simply not sent, so the sentinels are gone, and a plain RuuviTag's record is
 * three bytes smaller than it was in version 1 while a Ruuvi Air's carries CO₂
 * and particulates it had no room for before.
 *
 * <p>Version 1 is not deprecated and is not going anywhere. A device flashed two
 * summers ago keeps sending it, and the version byte is the first thing read
 * here precisely so that it can. {@code protocol-evolution.md} is where the
 * shape of version 2 was decided and why.
 *
 * <h2>An unknown field type is stepped over</h2>
 *
 * <p>Every field is the same three bytes, so a type this server has never heard
 * of costs exactly one skip. That is what lets a firmware start reporting
 * something before a server knows what to do with it — the alternative, refusing
 * the packet, would mean the temperature in it is lost too, over a field nobody
 * asked for.
 *
 * <h2>The packet is unauthenticated</h2>
 *
 * <p>A UDP sender address is trivial to spoof, so the server must not trust the
 * data for anything beyond displaying it. If this ever gets another use, the
 * format needs something like an HMAC and a version bump.
 */
public final class PacketDecoder {

    /** The version this server's own firmware sends today. */
    public static final int VERSION = 2;

    /** Still spoken by every device flashed before the Ruuvi Air arrived. */
    public static final int VERSION_LEGACY = 1;

    private static final int HEADER_SIZE = 8;
    private static final int ID_SIZE = 4;
    private static final int MAX_SENSORS = 8;

    /* Version 1: a fixed record and its sentinels. */
    private static final int LEGACY_SENSOR_SIZE = 8;
    private static final short TEMPERATURE_INVALID = (short) 0x8000;
    private static final int HUMIDITY_INVALID = 0xFFFF;

    /* Version 2: the record header, and one field. */
    private static final int SENSOR_HEADER_SIZE = ID_SIZE + 1;
    private static final int FIELD_SIZE = 3;
    private static final int MAX_FIELDS = 7;

    /* The field type registry, as Protocol.h defines it. Numbers are permanent. */
    private static final int FIELD_TEMPERATURE = 1;
    private static final int FIELD_HUMIDITY = 2;
    private static final int FIELD_CO2 = 4;
    private static final int FIELD_PM25 = 5;

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
        if (version != VERSION && version != VERSION_LEGACY) {
            throw new IllegalArgumentException("Unknown protocol version " + version);
        }

        String deviceId = readId(buffer);
        int count = Byte.toUnsignedInt(buffer.get());
        int sequence = Short.toUnsignedInt(buffer.getShort());

        if (count > MAX_SENSORS) {
            throw new IllegalArgumentException(
                    "Sensor count " + count + " exceeds the limit " + MAX_SENSORS);
        }

        List<SensorMeasurement> sensors = version == VERSION_LEGACY
                ? readLegacySensors(buffer, count, length)
                : readSensors(buffer, count);

        return new DeviceMeasurement(deviceId, sequence, receivedAt, List.copyOf(sensors));
    }

    /**
     * Version 1, whose length is known before anything is read — so it is checked
     * first and a truncated packet never reaches the loop.
     */
    private static List<SensorMeasurement> readLegacySensors(ByteBuffer buffer, int count,
                                                             int length) {
        int expected = HEADER_SIZE + count * LEGACY_SENSOR_SIZE;
        if (length != expected) {
            throw new IllegalArgumentException("Length " + length + " does not match sensor count "
                    + count + " (expected " + expected + ")");
        }

        List<SensorMeasurement> sensors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String sensorId = readId(buffer);
            Double temperature = decodeTemperature(buffer.getShort());
            Double humidity = decodeLegacyHumidity(Short.toUnsignedInt(buffer.getShort()));
            sensors.add(new SensorMeasurement(sensorId, temperature, humidity, null, null));
        }
        return sensors;
    }

    /**
     * Version 2, whose length depends on what each sensor had to say — so the
     * frame is checked as it is walked, one record at a time.
     */
    private static List<SensorMeasurement> readSensors(ByteBuffer buffer, int count) {
        List<SensorMeasurement> sensors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            require(buffer, SENSOR_HEADER_SIZE, "a sensor record");
            String sensorId = readId(buffer);
            int fieldCount = Byte.toUnsignedInt(buffer.get());
            if (fieldCount > MAX_FIELDS) {
                throw new IllegalArgumentException("Sensor " + sensorId + " claims " + fieldCount
                        + " fields, more than the limit " + MAX_FIELDS);
            }
            require(buffer, fieldCount * FIELD_SIZE, fieldCount + " field(s)");

            Double temperature = null;
            Double humidity = null;
            Double co2 = null;
            Double pm25 = null;
            for (int field = 0; field < fieldCount; field++) {
                int type = Byte.toUnsignedInt(buffer.get());
                short raw = buffer.getShort();
                switch (type) {
                    case FIELD_TEMPERATURE -> temperature = raw / 100.0;
                    case FIELD_HUMIDITY -> humidity = Short.toUnsignedInt(raw) / 100.0;
                    case FIELD_CO2 -> co2 = (double) Short.toUnsignedInt(raw);
                    case FIELD_PM25 -> pm25 = Short.toUnsignedInt(raw) / 10.0;
                    default -> {
                        /*
                           A type from a newer firmware than this server. Already
                           stepped over by having read its three bytes, which is
                           the whole benefit of every field being the same size.
                        */
                    }
                }
            }
            sensors.add(new SensorMeasurement(sensorId, temperature, humidity, co2, pm25));
        }

        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException(
                    buffer.remaining() + " byte(s) left over after " + count + " sensor(s)");
        }
        return sensors;
    }

    private static void require(ByteBuffer buffer, int bytes, String what) {
        if (buffer.remaining() < bytes) {
            throw new IllegalArgumentException("Packet ends in the middle of " + what
                    + ": " + buffer.remaining() + " byte(s) left, " + bytes + " needed");
        }
    }

    private static String readId(ByteBuffer buffer) {
        byte[] raw = new byte[ID_SIZE];
        buffer.get(raw);
        return new String(raw, StandardCharsets.US_ASCII).trim();
    }

    private static Double decodeTemperature(short raw) {
        return raw == TEMPERATURE_INVALID ? null : raw / 100.0;
    }

    private static Double decodeLegacyHumidity(int raw) {
        return raw == HUMIDITY_INVALID ? null : raw / 100.0;
    }
}
