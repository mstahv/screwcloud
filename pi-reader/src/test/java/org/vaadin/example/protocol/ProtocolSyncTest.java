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
        assertEquals(constant("PROTOCOL_ID_SIZE"), Protocol.ID_SIZE);
        assertEquals(constant("PROTOCOL_MAX_SENSORS"), Protocol.MAX_SENSORS);
    }

    @Test
    void theSensorRecordSizesMatch() {
        assertEquals(constant("PROTOCOL_SENSOR_HEADER_SIZE"), Protocol.SENSOR_HEADER_SIZE);
        assertEquals(constant("PROTOCOL_FIELD_SIZE"), Protocol.FIELD_SIZE);
        assertEquals(constant("PROTOCOL_MAX_FIELDS"), Protocol.MAX_FIELDS);
    }

    /**
     * The registry numbers, which are the part that must never drift: a type
     * means one measurement with one scaling, in every firmware and every
     * reader, forever. Two implementations disagreeing about what 4 means would
     * file carbon dioxide as particulates and nothing would look broken.
     *
     * <p>The reserved ones are checked too, even though nothing sends them yet.
     * Reserving a number is only worth anything if both sides reserve the same
     * one.
     */
    @Test
    void theFieldTypeRegistryMatches() {
        assertEquals(constant("PROTOCOL_FIELD_TEMPERATURE"), Protocol.FIELD_TEMPERATURE);
        assertEquals(constant("PROTOCOL_FIELD_HUMIDITY"), Protocol.FIELD_HUMIDITY);
        assertEquals(constant("PROTOCOL_FIELD_PRESSURE"), Protocol.FIELD_PRESSURE);
        assertEquals(constant("PROTOCOL_FIELD_CO2"), Protocol.FIELD_CO2);
        assertEquals(constant("PROTOCOL_FIELD_PM25"), Protocol.FIELD_PM25);
        assertEquals(constant("PROTOCOL_FIELD_VOC"), Protocol.FIELD_VOC);
        assertEquals(constant("PROTOCOL_FIELD_NOX"), Protocol.FIELD_NOX);
    }

    /**
     * The scaling itself. Not parsed into a number — it is an expression, not a
     * constant — but a change to it shows up here, which is the point: the two
     * sides multiply by the same hundred, and rounding is what decides the last
     * digit of every temperature ever stored.
     */
    @Test
    void theScalingIsStillAHundredthAndStillRounded() {
        assertTrue(expression("addTemperature").contains("celsius * 100.0f"),
                "temperature scaling changed in the firmware: " + expression("addTemperature"));
        assertTrue(expression("addTemperature").contains("lroundf"),
                "the firmware stopped rounding temperatures");
        assertTrue(expression("addScaled").contains("lroundf"),
                "the firmware stopped rounding the scaled fields");
    }

    /**
     * The scale each unsigned field is sent in, which since version 2 lives at
     * the call rather than in a function of its own. A hundredth for humidity, a
     * whole ppm for carbon dioxide, a tenth for particulates — get one of these
     * wrong in one implementation and the number is off by a factor of ten in a
     * way nothing else notices.
     */
    @Test
    void theUnsignedFieldsAreSentInTheSameUnits() {
        assertTrue(call("PROTOCOL_FIELD_HUMIDITY").contains("100.0f"),
                "humidity scaling changed in the firmware: " + call("PROTOCOL_FIELD_HUMIDITY"));
        assertTrue(call("PROTOCOL_FIELD_CO2").contains("1.0f"),
                "CO2 scaling changed in the firmware: " + call("PROTOCOL_FIELD_CO2"));
        assertTrue(call("PROTOCOL_FIELD_PM25").contains("10.0f"),
                "PM2.5 scaling changed in the firmware: " + call("PROTOCOL_FIELD_PM25"));
    }

    /**
     * And the rounding agrees in practice. Both sides round half away from zero,
     * which for a negative temperature is not what a plain cast would do.
     */
    @Test
    void roundingAgreesWithTheFirmwareAtAHalf() {
        assertEquals(2145, Protocol.temperature(21.445).value());
        assertEquals(-1235, (short) Protocol.temperature(-12.345).value());
    }

    /** The {@code addScaled} call that sends one field, with its scale and limit. */
    private static String call(String fieldType) {
        Matcher matcher = Pattern.compile("addScaled\\([^;]*" + fieldType + "[^;]*;").matcher(source);
        assertTrue(matcher.find(), fieldType + " is not sent by " + HEADER);
        return matcher.group();
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
