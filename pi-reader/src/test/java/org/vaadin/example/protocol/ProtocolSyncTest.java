package org.vaadin.example.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Keeps this reader's idea of the wire format tied to the firmware's.
 *
 * <p>There are now three implementations of one format — two sketches and this —
 * and nothing but a test stops them drifting apart. The failure it prevents is a
 * quiet one: a packet that decodes into plausible numbers that are simply wrong,
 * from one device out of several.
 *
 * <p>The values are read out of {@code Protocol.h} and compared against the
 * constants here. That is a different bargain from the server's own
 * {@code ProtocolSyncTest}, which compares the two sketches against each other and
 * holds no opinion of its own: this side is Java and cannot be compared to C by
 * regular expression, so the header is treated as the source of truth and this
 * file as the copy.
 *
 * <p>The sketch lives outside this module. When it is not there — this module
 * built on its own, out of the repository — the test skips rather than fails, so a
 * standalone build stays green while a full checkout is still checked.
 */
class ProtocolSyncTest {

    private static final Path HEADER =
            Path.of("..", "temperature-reader", "Protocol.h");

    private static String source;

    @BeforeAll
    static void readTheFirmwareHeader() {
        Assumptions.assumeTrue(Files.exists(HEADER),
                "Protocol.h is not next to this module, so there is nothing to compare against");
        try {
            source = Files.readString(HEADER);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theHeaderSizesMatch() {
        assertEquals(constant("PROTOCOL_VERSION"), Protocol.VERSION);
        assertEquals(constant("PROTOCOL_HEADER_SIZE"), Protocol.HEADER_SIZE);
        assertEquals(constant("PROTOCOL_SENSOR_SIZE"), Protocol.SENSOR_SIZE);
        assertEquals(constant("PROTOCOL_ID_SIZE"), Protocol.ID_SIZE);
        assertEquals(constant("PROTOCOL_MAX_SENSORS"), Protocol.MAX_SENSORS);
    }

    @Test
    void theSentinelsMatch() {
        assertEquals(constant("PROTOCOL_TEMPERATURE_INVALID"),
                Protocol.TEMPERATURE_INVALID & 0xFFFF);
        assertEquals(constant("PROTOCOL_HUMIDITY_INVALID"), Protocol.HUMIDITY_INVALID);
    }

    /**
     * The scaling itself. Not parsed into a number — it is an expression, not a
     * constant — but a change to it shows up here, which is the point: the two
     * sides multiply by the same hundred, and rounding is what decides the last
     * digit of every temperature ever stored.
     */
    @Test
    void theScalingIsStillAHundredthAndStillRounded() {
        assertTrue(expression("encodeTemperature").contains("celsius * 100.0f"),
                "temperature scaling changed in the firmware: " + expression("encodeTemperature"));
        assertTrue(expression("encodeTemperature").contains("lroundf"),
                "the firmware stopped rounding temperatures");
        assertTrue(expression("encodeHumidity").contains("percent * 100.0f"),
                "humidity scaling changed in the firmware: " + expression("encodeHumidity"));
        assertTrue(expression("encodeHumidity").contains("lroundf"),
                "the firmware stopped rounding humidities");
    }

    /**
     * And the rounding agrees in practice. Both sides round half away from zero,
     * which for a negative temperature is not what a plain cast would do.
     */
    @Test
    void roundingAgreesWithTheFirmwareAtAHalf() {
        assertEquals(2145, Protocol.encodeTemperature(21.445));
        assertEquals(-1235, Protocol.encodeTemperature(-12.345));
    }

    private static int constant(String name) {
        Matcher matcher = Pattern
                .compile(name + "\\s*=\\s*\\(?[a-z0-9_]*\\)?\\s*(0[xX][0-9A-Fa-f]+|\\d+)")
                .matcher(source);
        assertTrue(matcher.find(), name + " was not found in " + HEADER);
        String value = matcher.group(1);
        return value.toLowerCase().startsWith("0x")
                ? Integer.parseInt(value.substring(2), 16)
                : Integer.parseInt(value);
    }

    private static String expression(String function) {
        Matcher matcher = Pattern
                .compile(function + "\\s*\\([^)]*\\)\\s*\\{(.*?)\\n  \\}", Pattern.DOTALL)
                .matcher(source);
        return Optional.of(matcher).filter(Matcher::find).map(m -> m.group(1))
                .orElseThrow(() -> new AssertionError(function + " was not found in " + HEADER));
    }
}
