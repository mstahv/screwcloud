package org.vaadin.example.ruuvi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * The decoding, against Ruuvi's own published test vectors.
 *
 * <p>Using their vectors rather than numbers of my own is the whole point: this is
 * the third implementation of the format in this repository, and a test written
 * from the same reading of the specification as the code would agree with it
 * whether or not either was right.
 *
 * @see <a href="https://docs.ruuvi.com/communication/bluetooth-advertisements/data-format-5-rawv2">Data
 *      Format 5 (RAWv2)</a>
 */
class DataFormat5Test {

    private static final Instant AT = Instant.parse("2026-08-14T09:00:00Z");

    /** Ruuvi's "valid data" vector. */
    private static final String VALID =
            "0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F";

    /** Their "maximum values" and "minimum values" vectors. */
    private static final String MAXIMUM =
            "057FFFFFFEFFFE7FFF7FFF7FFFFFDEFEFFFECBB8334C884F";
    private static final String MINIMUM =
            "058001000000008001800180010000000000CBB8334C884F";

    /** And the one where every field says "I do not know". */
    private static final String INVALID =
            "058000FFFFFFFF800080008000FFFFFFFFFFFFFFFFFFFFFF";

    @Test
    void theValidVectorDecodesToTheDocumentedValues() {
        RuuviReading reading = parse(VALID);

        assertEquals(24.30, reading.temperature(), 0.001);
        assertEquals(53.49, reading.humidity(), 0.001);
        assertEquals(1000.44, reading.pressure(), 0.001);   // 100044 Pa
        assertEquals(2.977, reading.batteryVoltage(), 0.001);
        assertEquals(4, reading.txPower());
        assertEquals(66, reading.movementCounter());
        assertEquals(205, reading.sequenceNumber());
        assertEquals("CB:B8:33:4C:88:4F", reading.macAddress());
    }

    @Test
    void theMaximumVectorDecodesToTheDocumentedValues() {
        RuuviReading reading = parse(MAXIMUM);

        assertEquals(163.835, reading.temperature(), 0.001);
        assertEquals(163.8350, reading.humidity(), 0.001);
        assertEquals(1155.34, reading.pressure(), 0.001);
        assertEquals(3.646, reading.batteryVoltage(), 0.001);
        assertEquals(20, reading.txPower());
        assertEquals(254, reading.movementCounter());
        assertEquals(65534, reading.sequenceNumber());
    }

    @Test
    void theMinimumVectorDecodesToTheDocumentedValues() {
        RuuviReading reading = parse(MINIMUM);

        assertEquals(-163.835, reading.temperature(), 0.001);
        assertEquals(0.0, reading.humidity(), 0.001);
        assertEquals(500.0, reading.pressure(), 0.001);
        assertEquals(1.6, reading.batteryVoltage(), 0.001);
        assertEquals(-40, reading.txPower());
        assertEquals(0, reading.movementCounter());
        assertEquals(0, reading.sequenceNumber());
    }

    /**
     * Every field absent. None of them may become a number: a humidity of 0 % and
     * "this tag does not measure humidity" are different things, and a Pro 2in1
     * sends the second on every broadcast.
     */
    @Test
    void theInvalidVectorDecodesToNothingRatherThanToZeroes() {
        RuuviReading reading = parse(INVALID);

        assertNull(reading.temperature());
        assertNull(reading.humidity());
        assertNull(reading.pressure());
        assertNull(reading.batteryVoltage());
        assertNull(reading.txPower());
    }

    @Test
    void anAdvertisementFromSomethingElseIsNotAReading() {
        assertTrue(DataFormat5.parse(new byte[0], AT, null).isEmpty());
        assertTrue(DataFormat5.parse(null, AT, null).isEmpty());
        // Data format 3, the older one, which this does not decode.
        assertTrue(DataFormat5.parse(HexFormat.of().parseHex(
                "03291A1ECE1EFC18F94202CA0B5300000000BB"), AT, null).isEmpty(),
                "a format this does not understand must be skipped, not misread");
        // Right format byte, truncated payload.
        assertTrue(DataFormat5.parse(HexFormat.of().parseHex("0512FC5394"), AT, null).isEmpty());
    }

    /**
     * The identifier has to match both firmwares byte for byte, or the same tag
     * arrives at the server as two sensors depending on which box heard it.
     */
    @Test
    void theSensorIdComesFromTheLowTwelveBitsOfTheAddress() {
        // ...88:4F -> the low nibble of 0x88 and all of 0x4F
        assertEquals("R84F", parse(VALID).sensorId());
    }

    private static RuuviReading parse(String hex) {
        return DataFormat5.parse(HexFormat.of().parseHex(hex), AT, (short) -55).orElseThrow();
    }
}
