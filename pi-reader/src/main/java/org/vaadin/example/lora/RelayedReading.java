package org.vaadin.example.lora;

import java.time.Instant;

import org.vaadin.example.sensor.Reading;

/**
 * One sensor out of a packet that arrived over the air.
 *
 * <p>These exist so that a device out of WiFi range shows up on this machine's own
 * page rather than only in the log. The relay itself does not need them — the bytes
 * are forwarded to the server untouched, which is the whole point — but a reader
 * standing in front of the Pi wants to see the temperature, not a line saying that
 * sixteen bytes went past.
 *
 * <p><b>Display only.</b> They are kept apart from the readings this machine heard
 * itself, and {@link org.vaadin.example.ruuvi.TagRegistry} is careful never to put
 * them in a packet: they have already been sent, as themselves, by the relay. Adding
 * them to this reader's own upload would report the same measurement twice under two
 * different device identifiers, and the server would believe both.
 *
 * @param deviceId       the device that sent the packet, from its header
 * @param packetSensorId what that device calls the sensor
 * @param soleSensor     whether it was the only sensor in the packet
 * @param temperature    degrees Celsius, or null where the packet said "no value"
 * @param humidity       relative humidity in percent, or null
 * @param rssi           how strongly this machine heard the packet it came in
 * @param receivedAt     when the packet arrived
 */
public record RelayedReading(String deviceId, String packetSensorId, boolean soleSensor,
                             Double temperature, Double humidity, Short rssi,
                             Instant receivedAt) implements Reading {

    /**
     * What this is called on the page, and the key a name is remembered under.
     *
     * <p>Not what the packet calls it. A bare Pico reporting its own die temperature
     * calls that sensor {@code CPU}, which on this page would sit next to this
     * machine's own {@code CPU} saying nothing about where it came from or which of
     * the two it is. Leading with {@code LoRa} says the useful thing first: this one
     * arrived over the air.
     *
     * <p>The device follows, and that is not decoration. This string is what {@link
     * org.vaadin.example.names.SensorNames} keys a name on, so two nodes both called
     * {@code LoRa} would share a name — rename one and the other changes too. The
     * device identifier is what makes them separate measuring points, so it belongs
     * in the identifier.
     *
     * <p>The packet's own sensor name is added only when there is more than one to
     * tell apart. For the single-sensor node this was written for, {@code CPU} adds
     * nothing but width.
     */
    @Override
    public String sensorId() {
        return soleSensor ? "LoRa " + deviceId : "LoRa " + deviceId + "/" + packetSensorId;
    }

    /**
     * The key this is stored under, which has to be unique across every source this
     * reader has.
     *
     * <p>Not an address — nothing that arrives over LoRa has one. The device and
     * sensor together are what identifies a measuring point in the wire format, so
     * they are what identifies it here. Prefixed, because a RuuviTag's address and a
     * Thingy's are both real addresses and this is not: a reader looking at the key
     * should be able to tell that this one came from the air.
     *
     * <p>Built from the packet's own names rather than from {@link #sensorId()},
     * which is a label and could be reworded without meaning that the thing being
     * measured has changed.
     */
    @Override
    public String macAddress() {
        return "lora:" + deviceId + "/" + packetSensorId;
    }
}
