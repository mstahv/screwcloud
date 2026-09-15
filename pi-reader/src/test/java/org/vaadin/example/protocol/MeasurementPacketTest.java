package org.vaadin.example.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                //  ver  P  I  0  1  cnt  seq     R  0  B  F  n  t 2150  t 4525
                    "01".replace("01", "02") + "50493031" + "01" + "0007"
                    + "52304246" + "02" + "01" + "0866" + "02" + "11AD"),
                packet);
    }

    /**
     * The packet version 2 exists for: a sensor that measures the air, carrying
     * four fields where version 1 had room for two.
     */
    @Test
    void aSensorThatMeasuresTheAirSendsFourFields() {
        byte[] packet = MeasurementPacket.encode("PI01", 1,
                List.of(new SensorReading("RA01", 21.5, 45.25, 812.0, 6.3)));

        assertArrayEquals(HexFormat.of().parseHex(
                //  ver  P  I  0  1  cnt  seq     R  A  0  1  n
                    "02" + "50493031" + "01" + "0001" + "5241" + "3031" + "04"
                    // t 2150    t 4525    co2 812   pm25 63
                    + "01" + "0866" + "02" + "11AD" + "04" + "032C" + "05" + "003F"),
                packet);
    }

    /**
     * A plain tag is smaller than it was in version 1, which had eight bytes per
     * sensor whatever the sensor had to say.
     */
    @Test
    void aSensorOnlySendsWhatItMeasured() {
        byte[] both = MeasurementPacket.encode("PI01", 0,
                List.of(new SensorReading("R0BF", 21.5, 45.25)));
        byte[] temperatureOnly = MeasurementPacket.encode("PI01", 0,
                List.of(new SensorReading("R0BF", 21.5, null)));

        assertEquals(Protocol.HEADER_SIZE + Protocol.SENSOR_HEADER_SIZE
                + 2 * Protocol.FIELD_SIZE, both.length);
        assertEquals(Protocol.HEADER_SIZE + Protocol.SENSOR_HEADER_SIZE
                + Protocol.FIELD_SIZE, temperatureOnly.length);
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

        // header 8, id 4, field count 1, then the type byte: the value is at 14.
        assertEquals(-1234, (short) (((packet[14] & 0xFF) << 8) | (packet[15] & 0xFF)));
    }

    /**
     * A missing value is no field at all. Version 1 had to send a sentinel,
     * because a fixed record cannot leave anything out; here the sensor record
     * is simply empty, which is both smaller and less to get wrong.
     */
    @Test
    void aMissingValueIsNoFieldAtAll() {
        byte[] packet = MeasurementPacket.encode("PI01", 0,
                List.of(new SensorReading("R0BF", null, null)));

        assertEquals(Protocol.HEADER_SIZE + Protocol.SENSOR_HEADER_SIZE, packet.length);
        assertEquals(0, packet[12], "the sensor should claim no fields");
    }

    /** A value too large for the field is left out rather than wrapped. */
    @Test
    void aValueThatWillNotFitIsMissingRatherThanWrong() {
        assertNull(Protocol.temperature(400.0));
        assertNull(Protocol.temperature(Double.NaN));
        assertNull(Protocol.humidity(-1.0));
        assertNull(Protocol.co2(70000.0));
        assertNull(Protocol.pm25(-0.1));
    }

    @Test
    void theLengthFollowsFromWhatEachSensorMeasured() {
        assertEquals(Protocol.HEADER_SIZE,
                MeasurementPacket.encode("PI01", 0, List.of()).length);
        assertEquals(Protocol.HEADER_SIZE + 3 * (Protocol.SENSOR_HEADER_SIZE + Protocol.FIELD_SIZE),
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
