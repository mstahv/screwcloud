package org.vaadin.example.thingy;

import java.time.Instant;
import java.util.Optional;

import org.vaadin.example.sensor.Reading;

/**
 * One measurement from a Nordic Thingy:52.
 *
 * <p>Far smaller than a RuuviTag's, because a Thingy sends each quantity on a
 * characteristic of its own rather than packing everything into one
 * advertisement. What arrives here is whatever the last notification carried.
 *
 * @param macAddress  the Bluetooth address, which is what BlueZ knows it by
 * @param temperature degrees Celsius, or null before the first notification
 * @param humidity    relative humidity in percent, or null
 * @param rssi        signal strength, or null — BlueZ often stops reporting it
 *                    once a device is connected rather than merely advertising
 * @param receivedAt  when this reader last saw a value change
 */
public record ThingyReading(String macAddress, Double temperature, Double humidity,
                            Short rssi, Instant receivedAt) implements Reading {

    /**
     * {@code T} and the low twelve bits of the address, exactly as a RuuviTag's is
     * derived — the same rule, a different letter.
     *
     * <p>The letter is what keeps the two apart. A Thingy and a RuuviTag whose
     * addresses happened to end in the same twelve bits would otherwise arrive at
     * the server as one sensor that could not make its mind up about the
     * temperature.
     */
    @Override
    public String sensorId() {
        return "T%03X".formatted(lowTwelveBits(macAddress));
    }

    /**
     * The last twelve bits of an address written in the usual colon notation.
     *
     * <p>Zero if the address is not in that shape. That cannot happen with an
     * address from BlueZ, and a reading with an odd identifier is a better outcome
     * than a reader that throws on the poll thread.
     */
    private static int lowTwelveBits(String address) {
        String[] parts = address.split(":");
        if (parts.length < 6) {
            return 0;
        }
        try {
            int fifth = Integer.parseInt(parts[4], 16);
            int sixth = Integer.parseInt(parts[5], 16);
            return ((fifth & 0x0F) << 8) | (sixth & 0xFF);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Temperature as the Thingy's environment service sends it: a signed whole
     * number of degrees and an unsigned hundredths part, two bytes in that order.
     *
     * <p>Below zero the encoding is ambiguous in Nordic's own firmware — it
     * truncates towards zero and then casts a negative remainder to an unsigned
     * byte — so the hundredths are read here as a distance from the whole number
     * rather than as something to add to it. That makes {@code -5, 25} read as
     * -5.25, which is what a person writing those two numbers down would mean, and
     * it is exact at every whole degree either way.
     */
    public static Optional<Double> decodeTemperature(byte[] value) {
        if (value == null || value.length < 2) {
            return Optional.empty();
        }
        int whole = value[0];               // signed
        int hundredths = value[1] & 0xFF;   // unsigned
        double magnitude = Math.abs(whole) + hundredths / 100.0;
        return Optional.of(whole < 0 ? -magnitude : magnitude);
    }

    /** Humidity is one unsigned byte of whole percent. */
    public static Optional<Double> decodeHumidity(byte[] value) {
        if (value == null || value.length < 1) {
            return Optional.empty();
        }
        return Optional.of((double) (value[0] & 0xFF));
    }
}
