package org.vaadin.example.ruuvi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * The decoding, against the vectors in Ruuvi's own
 * <a href="https://github.com/ruuvi/ruuvi.endpoints.c">ruuvi.endpoints.c</a>
 * tests — the code the Ruuvi Air firmware encodes with. Using their vectors
 * rather than numbers of my own is the point, for the same reason as in
 * {@link DataFormat5Test}: a test written from the same reading of the
 * specification as the code would agree with it whether or not either was right.
 */
class DataFormat6Test {

    private static final Instant AT = Instant.parse("2026-08-26T09:00:00Z");

    /** ruuvi.endpoints.c's "valid data": 29.5 °C, 55.3 %, 201 ppm CO₂ and so on. */
    private static final String VALID = "06170C5668C79E007000C90501D94ACD004C884F";

    /** And the one where every sensor says "I do not know" — the warm-up state. */
    private static final String INVALID = "068000FFFFFFFFFFFFFFFFFFFFFFFFFFD0FFFFFF";

    @Test
    void theValidVectorDecodesToTheDocumentedValues() {
        AirReading reading = parse(VALID);

        assertEquals(29.5, reading.temperature(), 0.001);
        assertEquals(55.3, reading.humidity(), 0.001);
        assertEquals(1011.02, reading.pressure(), 0.001);   // 101102 Pa
        assertEquals(11.2, reading.pm25(), 0.001);
        assertEquals(201.0, reading.co2(), 0.001);
        assertEquals(10.0, reading.voc(), 0.001);
        assertEquals(2.0, reading.nox(), 0.001);
        /*
           The firmware encoded 13027 lux; the byte holds 254 logarithmic steps,
           so what comes back is the same illumination, not the same number.
        */
        assertEquals(13027.0, reading.luminosity(), 13027.0 * 0.03);
        assertEquals(47.6, reading.soundAvgDba(), 0.001);
        assertEquals(205, reading.sequence());
        assertFalse(reading.calibrating());
    }

    /**
     * The identifier and the address, from the three bytes the format carries.
     * The id must equal what the full address would give — this is the same tag
     * tail as data format 5's vectors, and it has to file as the same sensor.
     */
    @Test
    void theIdentifierMatchesWhatTheFullAddressWouldGive() {
        AirReading reading = parse(VALID);

        assertEquals("R84F", reading.sensorId());
        assertEquals("4C:88:4F", reading.macAddress());
    }

    /*
       Every "not available" marker becomes null, never a plausible number. The
       vector's flags byte carries the ninth bits that turn the 9-bit fields into
       their 0x1FF sentinels — exactly the encoding detail worth a test.
    */
    @Test
    void everyInvalidMarkerBecomesNull() {
        AirReading reading = parse(INVALID);

        assertNull(reading.temperature());
        assertNull(reading.humidity());
        assertNull(reading.pressure());
        assertNull(reading.pm25());
        assertNull(reading.co2());
        assertNull(reading.voc());
        assertNull(reading.nox());
        assertNull(reading.luminosity());
        assertNull(reading.soundAvgDba());
    }

    @Test
    void aDifferentFormatIsNotClaimed() {
        byte[] formatFive = HexFormat.of().parseHex(VALID);
        formatFive[0] = 0x05;

        assertTrue(DataFormat6.parse(formatFive, AT, null).isEmpty(),
                "Format 5 belongs to the other decoder");
        assertTrue(DataFormat6.parse(new byte[3], AT, null).isEmpty(),
                "A truncated payload is not a reading");
    }

    private static AirReading parse(String hex) {
        return DataFormat6.parse(HexFormat.of().parseHex(hex), AT, (short) -60)
                .orElseThrow(() -> new AssertionError("The vector should decode"));
    }
}
