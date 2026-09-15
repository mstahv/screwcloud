package org.vaadin.example.lora;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough of a received packet to say something about it on screen.
 *
 * <p>The bytes are forwarded to the server untouched — that is the whole point
 * of a relay, and re-encoding a packet is a way to introduce a difference
 * between what was sent and what arrives. But a line that says only "48 bytes
 * arrived" is not worth reading, so the header is peeked at for the two facts
 * that make it meaningful: which device sent it, and how many sensors it
 * carries.
 *
 * <p>Nothing here decides whether the packet is forwarded. A packet that does
 * not look like a measurement is still passed on, because the server is the one
 * that decodes them and it already refuses what it cannot read — with a log line
 * naming the first bytes.
 *
 * <p>Both wire versions are taken apart here, because both turn up: the nodes on
 * the far end of the radio run the same {@code Protocol.h} as everything else,
 * and one flashed last summer still sends version 1. The relay does not care
 * either way — the bytes go on untouched whatever they are — so this is only
 * about what the local page can say about them.
 *
 * <p>The sizes below are this class's own rather than {@code Protocol}'s, which
 * describes the version being sent today. Version 1's shape has to stay written
 * down somewhere for as long as a device somewhere still speaks it, and a
 * constant shared with the format that moved on is a constant that silently
 * stops describing what it is used for.
 */
public record LoraPacket(byte[] bytes, int rssiDbm, double snrDb) {

    private static final int VERSION_LEGACY = 1;
    private static final int VERSION = 2;

    private static final int HEADER_SIZE = 8;
    private static final int ID_SIZE = 4;

    /* Version 1: a fixed record, and the sentinels a fixed record needs. */
    private static final int LEGACY_SENSOR_SIZE = 8;
    private static final short TEMPERATURE_INVALID = (short) 0x8000;
    private static final int HUMIDITY_INVALID = 0xFFFF;

    /* Version 2: the record header, and one field. */
    private static final int SENSOR_HEADER_SIZE = 5;
    private static final int FIELD_SIZE = 3;
    private static final int FIELD_TEMPERATURE = 1;
    private static final int FIELD_HUMIDITY = 2;

    /** The device identifier in the header, or empty if there is no room for one. */
    public String deviceId() {
        if (bytes.length < HEADER_SIZE) {
            return "";
        }
        return new String(bytes, 1, ID_SIZE, StandardCharsets.US_ASCII).trim();
    }

    /** How many sensors the header claims, whether or not the bytes back it up. */
    public int sensorCount() {
        if (bytes.length < HEADER_SIZE) {
            return 0;
        }
        return bytes[5] & 0xFF;
    }

    /**
     * Whether this looks like one of ours: the right version byte and a length
     * that matches the sensor count. Used for the description only — an unlikely
     * packet is still relayed.
     */
    public boolean looksLikeAMeasurement() {
        if (bytes.length < HEADER_SIZE) {
            return false;
        }
        int version = bytes[0] & 0xFF;
        if (version == VERSION_LEGACY) {
            return bytes.length == HEADER_SIZE + sensorCount() * LEGACY_SENSOR_SIZE;
        }
        return version == VERSION && walk(null);
    }

    /**
     * The sensors inside, decoded, so they can be shown on this machine's page.
     *
     * <p>This is the one place where the relay looks inside a packet, and it changes
     * nothing about the relay: {@link #bytes()} still leaves untouched. Decoding here
     * is for the local display only, which is the half of this application that has
     * to keep working when the server cannot be reached — and a page that showed
     * "16 bytes arrived" while a thermometer sat unread in those bytes would be a
     * poor version of that.
     *
     * <p>Empty for anything that does not look like one of ours. A packet from
     * somebody else's device on the same frequency is still relayed, because the
     * server is the one that decides what it can read; it is simply not put on this
     * page as though it were a temperature.
     *
     * @param at when the packet arrived
     */
    public List<RelayedReading> readings(Instant at) {
        if (!looksLikeAMeasurement()) {
            return List.of();
        }
        List<RelayedReading> readings = new ArrayList<>(sensorCount());
        if ((bytes[0] & 0xFF) == VERSION_LEGACY) {
            for (int i = 0; i < sensorCount(); i++) {
                int at0 = HEADER_SIZE + i * LEGACY_SENSOR_SIZE;
                short rawTemperature =
                        (short) (((bytes[at0 + 4] & 0xFF) << 8) | (bytes[at0 + 5] & 0xFF));
                int rawHumidity = ((bytes[at0 + 6] & 0xFF) << 8) | (bytes[at0 + 7] & 0xFF);
                readings.add(reading(idAt(at0),
                        rawTemperature == TEMPERATURE_INVALID ? null : rawTemperature / 100.0,
                        rawHumidity == HUMIDITY_INVALID ? null : rawHumidity / 100.0, at));
            }
            return readings;
        }
        walk(record -> readings.add(
                reading(record.id(), record.temperature(), record.humidity(), at)));
        return readings;
    }

    /** One decoded sensor record, before it becomes something the page shows. */
    private record Record(String id, Double temperature, Double humidity) {
    }

    /**
     * Steps through a version 2 body, handing each sensor to {@code found}.
     *
     * <p>One walk for both jobs — checking that the frame hangs together, and
     * reading what is in it — because in a variable length format those are the
     * same act. A null consumer means only the first is wanted.
     *
     * <p>Fields this does not recognise are stepped over rather than refused.
     * The page shows a temperature and a humidity; a node that has started
     * reporting carbon dioxide over the radio is relayed intact to the server,
     * which does know what to do with it, and is not made to look broken here in
     * the meantime.
     *
     * @return whether the packet's own lengths add up
     */
    private boolean walk(java.util.function.Consumer<Record> found) {
        int at0 = HEADER_SIZE;
        for (int i = 0; i < sensorCount(); i++) {
            if (at0 + SENSOR_HEADER_SIZE > bytes.length) {
                return false;
            }
            String id = idAt(at0);
            int fields = bytes[at0 + ID_SIZE] & 0xFF;
            int body = at0 + SENSOR_HEADER_SIZE;
            if (body + fields * FIELD_SIZE > bytes.length) {
                return false;
            }

            Double temperature = null;
            Double humidity = null;
            for (int field = 0; field < fields; field++) {
                int at1 = body + field * FIELD_SIZE;
                int type = bytes[at1] & 0xFF;
                int raw = ((bytes[at1 + 1] & 0xFF) << 8) | (bytes[at1 + 2] & 0xFF);
                if (type == FIELD_TEMPERATURE) {
                    temperature = (short) raw / 100.0;
                } else if (type == FIELD_HUMIDITY) {
                    humidity = raw / 100.0;
                }
            }
            if (found != null) {
                found.accept(new Record(id, temperature, humidity));
            }
            at0 = body + fields * FIELD_SIZE;
        }
        return at0 == bytes.length;
    }

    private String idAt(int offset) {
        return new String(bytes, offset, ID_SIZE, StandardCharsets.US_ASCII).trim();
    }

    private RelayedReading reading(String sensorId, Double temperature, Double humidity,
                                   Instant at) {
        return new RelayedReading(deviceId(), sensorId, sensorCount() == 1,
                temperature, humidity, (short) rssiDbm, at);
    }

    /** One line for the page, in the terms a reader cares about. */
    public String describe() {
        if (!looksLikeAMeasurement()) {
            return "%d bytes from something else, RSSI %d dBm".formatted(bytes.length, rssiDbm);
        }
        return "%s, %d sensor%s, RSSI %d dBm, SNR %.1f dB".formatted(
                deviceId(), sensorCount(), sensorCount() == 1 ? "" : "s", rssiDbm, snrDb);
    }
}
