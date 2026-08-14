package org.vaadin.example.ruuvi;

import java.time.Duration;
import java.time.Instant;

/**
 * One advertisement from one RuuviTag, decoded.
 *
 * <p>A value is null when the tag sent the format's "invalid" marker for it, which
 * a Pro 2in1 does for humidity and any tag does for a sensor that has not settled
 * yet.
 *
 * @param mac              the address from the payload itself, six bytes
 * @param temperature      degrees Celsius, or null
 * @param humidity         relative humidity in percent, or null
 * @param pressure         hectopascals, or null
 * @param batteryVoltage   volts, or null
 * @param txPower          the tag's transmit power in dBm, or null
 * @param movementCounter  increments when the tag is moved
 * @param sequenceNumber   increments on every broadcast, so the same advertisement
 *                         read twice can be told from a new one
 * @param rssi             signal strength as the receiver saw it, or null
 * @param receivedAt       when this reader heard it
 */
public record RuuviReading(byte[] mac, Double temperature, Double humidity, Double pressure,
                           Double batteryVoltage, Integer txPower, int movementCounter,
                           int sequenceNumber, Short rssi, Instant receivedAt) {

    /**
     * The identifier the server ties readings to, derived exactly as both firmwares
     * derive it: {@code R} and the low twelve bits of the address.
     *
     * <p>It has to match them byte for byte. The same tag may be heard by this
     * reader and by a microcontroller, and if the two disagreed it would arrive as
     * two sensors.
     */
    public String sensorId() {
        return "R%03X".formatted(((mac[4] & 0x0F) << 8) | (mac[5] & 0xFF));
    }

    /** The address in the usual notation, for the log and for naming a tag. */
    public String macAddress() {
        StringBuilder text = new StringBuilder(17);
        for (byte b : mac) {
            if (!text.isEmpty()) {
                text.append(':');
            }
            text.append("%02X".formatted(b));
        }
        return text.toString();
    }

    public boolean isOlderThan(Duration age, Instant now) {
        return receivedAt.plus(age).isBefore(now);
    }
}
