package org.vaadin.example.lora;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.vaadin.example.protocol.Protocol;

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
 */
public record LoraPacket(byte[] bytes, int rssiDbm, double snrDb) {

    /** The device identifier in the header, or empty if there is no room for one. */
    public String deviceId() {
        if (bytes.length < Protocol.HEADER_SIZE) {
            return "";
        }
        return new String(bytes, 1, Protocol.ID_SIZE, StandardCharsets.US_ASCII).trim();
    }

    /** How many sensors the header claims, whether or not the bytes back it up. */
    public int sensorCount() {
        if (bytes.length < Protocol.HEADER_SIZE) {
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
        if (bytes.length < Protocol.HEADER_SIZE) {
            return false;
        }
        if ((bytes[0] & 0xFF) != Protocol.VERSION) {
            return false;
        }
        return bytes.length == Protocol.HEADER_SIZE + sensorCount() * Protocol.SENSOR_SIZE;
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
        for (int i = 0; i < sensorCount(); i++) {
            int at0 = Protocol.HEADER_SIZE + i * Protocol.SENSOR_SIZE;
            String sensorId = new String(bytes, at0, Protocol.ID_SIZE,
                    StandardCharsets.US_ASCII).trim();

            short rawTemperature = (short) (((bytes[at0 + 4] & 0xFF) << 8) | (bytes[at0 + 5] & 0xFF));
            int rawHumidity = ((bytes[at0 + 6] & 0xFF) << 8) | (bytes[at0 + 7] & 0xFF);

            readings.add(new RelayedReading(deviceId(), sensorId, sensorCount() == 1,
                    rawTemperature == Protocol.TEMPERATURE_INVALID ? null : rawTemperature / 100.0,
                    rawHumidity == Protocol.HUMIDITY_INVALID ? null : rawHumidity / 100.0,
                    (short) rssiDbm, at));
        }
        return readings;
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
