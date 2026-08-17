package org.vaadin.example.sensor;

import java.time.Duration;
import java.time.Instant;

/**
 * One measurement from one thing in this house, whatever kind of thing it is.
 *
 * <p>This exists because a second kind of sensor turned up. Everything downstream
 * of the radio — the registry, the day of history, the cards on the page, the
 * packet that goes to the server — cares about exactly what is here: what this
 * sensor is called, when it was heard, and the two numbers. None of it cares
 * whether the reading arrived in an advertisement from a RuuviTag or over a GATT
 * connection to a Thingy:52, and none of it should have to.
 *
 * <p>What is deliberately <em>not</em> here is everything specific to one kind of
 * device: Ruuvi's sequence number, movement counter, battery voltage and pressure
 * stay on {@code RuuviReading}, where the scanner that understands them can use
 * them.
 *
 * <p>Implementations are immutable records. They are written from a radio thread
 * and read from HTTP threads, and that is only safe because there is nothing to
 * mutate.
 */
public interface Reading {

    /**
     * The identifier the server ties readings to. Four characters at most, since
     * that is what the wire format carries.
     *
     * <p>Each kind of device derives it its own way, and the derivations must not
     * collide: a RuuviTag is {@code R} and twelve bits of its address, a Thingy:52
     * is {@code T} and the same. Two sensors arriving under one identifier would
     * be filed by the server as one sensor changing its mind.
     */
    String sensorId();

    /**
     * The Bluetooth address, which is what everything local is keyed by.
     *
     * <p>Not the identifier: that is derived from twelve bits of this and two
     * devices could in principle share one. Keying by the address means such a
     * clash is visible on the page rather than silently dropping a sensor.
     */
    String macAddress();

    /** Degrees Celsius, or null when this sensor had no value to give. */
    Double temperature();

    /** Relative humidity in percent, or null. */
    Double humidity();

    /** Signal strength as this receiver saw it, or null when it is not known. */
    Short rssi();

    /** When this reader heard it. */
    Instant receivedAt();

    default boolean isOlderThan(Duration age, Instant now) {
        return receivedAt().plus(age).isBefore(now);
    }
}
