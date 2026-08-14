package org.vaadin.example.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The bytes that leave this machine, checked against a packet written out by hand.
 *
 * <p>A literal rather than a second encoder: the point is what a receiver will
 * read, and an expectation derived from the same code would agree with any mistake
 * in it. {@link ProtocolSyncTest} is what keeps the constants honest.
 */
class MeasurementPacketTest {

    @Test
    void aPacketWithOneSensorIsTheDocumentedBytes() {
        byte[] packet = MeasurementPacket.encode("PI01", 7,
                List.of(new SensorReading("R0BF", 21.5, 45.25)));

        assertArrayEquals(HexFormat.of().parseHex(
                //  ver  P  I  0  1  cnt  seq     R  0  B  F   2150   4525
                    "01" + "50493031" + "01" + "0007" + "52304246" + "0866" + "11AD"),
                packet);
    }

    /** A short identifier is padded with spaces, which the receiver trims off. */
    @Test
    void shortIdentifiersArePaddedToFourBytes() {
        byte[] packet = MeasurementPacket.encode("PI", 0,
                List.of(new SensorReading("DHT", 0.0, 0.0)));

        assertEquals("PI  ", new String(packet, 1, 4));
        assertEquals("DHT ", new String(packet, 8, 4));
    }

    /** Below zero has to survive as a negative number, not as a large positive one. */
    @Test
    void aTemperatureBelowZeroIsSigned() {
        byte[] packet = MeasurementPacket.encode("PI01", 0,
                List.of(new SensorReading("R0BF", -12.34, null)));

        assertEquals(-1234, (short) (((packet[12] & 0xFF) << 8) | (packet[13] & 0xFF)));
    }

    /**
     * A missing value is the format's own sentinel. Sending zero instead would be
     * indistinguishable from a real zero, and zero degrees is a temperature people
     * care about.
     */
    @Test
    void aMissingValueIsSentAsTheSentinel() {
        byte[] packet = MeasurementPacket.encode("PI01", 0,
                List.of(new SensorReading("R0BF", null, null)));

        assertEquals(Protocol.TEMPERATURE_INVALID,
                (short) (((packet[12] & 0xFF) << 8) | (packet[13] & 0xFF)));
        assertEquals(Protocol.HUMIDITY_INVALID,
                ((packet[14] & 0xFF) << 8) | (packet[15] & 0xFF));
    }

    /** A value too large for the field is missing rather than wrapped. */
    @Test
    void aValueThatWillNotFitIsMissingRatherThanWrong() {
        assertEquals(Protocol.TEMPERATURE_INVALID, Protocol.encodeTemperature(400.0));
        assertEquals(Protocol.TEMPERATURE_INVALID, Protocol.encodeTemperature(Double.NaN));
        assertEquals(Protocol.HUMIDITY_INVALID, Protocol.encodeHumidity(-1.0));
    }

    @Test
    void theLengthFollowsFromTheSensorCount() {
        assertEquals(Protocol.HEADER_SIZE,
                MeasurementPacket.encode("PI01", 0, List.of()).length);
        assertEquals(Protocol.HEADER_SIZE + 3 * Protocol.SENSOR_SIZE,
                MeasurementPacket.encode("PI01", 0, List.of(
                        new SensorReading("A", 1.0, null),
                        new SensorReading("B", 2.0, null),
                        new SensorReading("C", 3.0, null))).length);
    }

    /**
     * More sensors than the format holds is refused here rather than silently
     * truncated, so the caller can choose what to leave out and say so.
     */
    @Test
    void moreSensorsThanFitIsRefused() {
        List<SensorReading> tooMany = java.util.stream.IntStream
                .rangeClosed(0, Protocol.MAX_SENSORS)
                .mapToObj(i -> new SensorReading("R%03X".formatted(i), 20.0, null))
                .toList();

        assertThrows(IllegalArgumentException.class,
                () -> MeasurementPacket.encode("PI01", 0, tooMany));
    }

    @Test
    void anIdentifierTooLongForTheFieldIsRefusedWhereItIsMade() {
        assertThrows(IllegalArgumentException.class,
                () -> new SensorReading("TOOLONG", 20.0, null));
    }

    /** The counter is 16 bits on the wire, as it is in the firmware. */
    @Test
    void theSequenceNumberWrapsAtSixteenBits() {
        byte[] packet = MeasurementPacket.encode("PI01", 65536 + 5, List.of());

        assertEquals(5, ((packet[6] & 0xFF) << 8) | (packet[7] & 0xFF));
    }
}
