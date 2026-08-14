package fi.mstahv.sensorhub.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that the two firmware variants still agree on the wire format this
 * package decodes.
 *
 * <p>{@code Protocol.h} is duplicated rather than shared because the Arduino build
 * does not reliably resolve includes that reach outside a sketch folder —
 * arduino-cli copies the sketch directory into its build path, so a
 * {@code ../shared/Protocol.h} include can break depending on the toolchain
 * version. A byte-identical copy plus this test is more robust than an include
 * that might silently stop working.
 *
 * <p>The Ruuvi Data Format 5 decoding is checked too. That format is externally
 * fixed and will not change, but a typo in one copy would produce silently wrong
 * temperatures from one board only — exactly the kind of bug that takes days to
 * notice.
 *
 * <p>Only what both variants must share is checked. The ESP32 variant deliberately
 * decodes fewer fields (no pressure, acceleration or battery), so the sketches are
 * not expected to match line for line.
 *
 * <p>Every check has the same shape: pull one value out of each sketch and compare
 * the two <b>against each other</b>. Comparing them to values written into the
 * test would make this a third copy of the wire format, so that changing the
 * scaling in both firmwares would fail until someone edited the test as well.
 * Here the test holds no opinion about what the values should be, only that they
 * agree.
 *
 * <p>This lives in the server module, which reaches outside its own directory to
 * read the firmware sources. That is deliberate: as a script it had to be
 * remembered, and a check nobody runs is a comment. The repository is always
 * checked out as a whole, so the sketches are there.
 */
class ProtocolSyncTest {

    /**
     * One firmware variant. The sketch is named after its directory because the
     * Arduino toolchain requires it, which is why one field is enough.
     */
    private record Firmware(String label, Path directory) {

        Path protocolHeader() {
            return directory.resolve("Protocol.h");
        }

        Path sketch() {
            return directory.resolve(directory.getFileName() + ".ino");
        }

        String sketchSource() {
            return read(sketch());
        }
    }

    private static final Firmware PICO = new Firmware("pico", firmwareDirectory("temperature-reader"));
    private static final Firmware ESP = new Firmware("esp32", firmwareDirectory("esp32-s3-reader"));

    /**
     * The sleeper carries the same wire format and none of the Ruuvi decoding, so
     * only the header is compared against it. Every copy of {@code Protocol.h} in
     * the repository has to be identical, and each new one makes that easier to
     * get wrong.
     */
    private static final Firmware SLEEPER = new Firmware("sleeper", firmwareDirectory("pico-sleeper"));

    private static final List<Firmware> ALL_FIRMWARES = List.of(PICO, ESP, SLEEPER);

    /**
     * One value that must be the same in both sketches, and the pattern that finds
     * it. The pattern has exactly one capturing group: what it captures is what
     * gets compared.
     */
    private record Rule(String label, Pattern pattern) {

        Rule(String label, String regex) {
            this(label, Pattern.compile(regex));
        }

        /** The captured value with its whitespace collapsed, or empty if absent. */
        Optional<String> extract(String source) {
            Matcher matcher = pattern.matcher(source);
            return matcher.find() ? Optional.of(normalize(matcher.group(1))) : Optional.empty();
        }

        @Override
        public String toString() {
            // Names the case in the test report.
            return label;
        }
    }

    /*
       The constants are matched with a lookahead rather than a word boundary:
       RUUVI_FORMAT_5 is a prefix of RUUVI_FORMAT_5_LEN, and an underscore is a
       word character, so \b would not separate them and the shorter rule would
       read the longer constant's value.

       The two sentinel checks capture the whole comparison by anchoring on the
       "if (...) {" that closes it, so an added cast or a changed literal shows up
       as a difference instead of being trimmed away.
    */
    private static List<Rule> sharedValues() {
        return List.of(
                new Rule("RUUVI_COMPANY_ID", "RUUVI_COMPANY_ID\\s*=\\s*([^;]+);"),
                new Rule("RUUVI_FORMAT_5", "RUUVI_FORMAT_5(?!_)\\s*=\\s*([^;]+);"),
                new Rule("RUUVI_FORMAT_5_LEN", "RUUVI_FORMAT_5_LEN\\s*=\\s*([^;]+);"),
                new Rule("temperature scaling", "temperature\\s*=\\s*(rawTemperature\\s*\\*[^;]+);"),
                new Rule("humidity scaling", "humidity\\s*=\\s*(rawHumidity\\s*\\*[^;]+);"),
                new Rule("temperature sentinel", "rawTemperature\\s*!=\\s*(.+?)\\)\\s*\\{"),
                new Rule("humidity sentinel", "rawHumidity\\s*!=\\s*(.+?)\\)\\s*\\{"),
                new Rule("MAC offset", "memcpy\\(mac,\\s*&data\\[(\\d+)\\]"));
    }

    /**
     * Every {@code static const} and {@code #define} in a sketch, so that a
     * constant nobody thought to list in {@link #sharedValues()} is still
     * compared.
     */
    private static final Pattern DECLARED_CONSTANT = Pattern.compile(
            "^\\s*(?:static\\s+const\\s+\\w+|#define)\\s+(\\w+)\\s*=?\\s*([^;\\n]+?);?\\s*(?://.*)?$",
            Pattern.MULTILINE);

    @Test
    void protocolHeaderIsByteIdenticalInEverySketch() throws IOException {
        for (Firmware firmware : ALL_FIRMWARES) {
            if (firmware != PICO) {
                assertHeaderMatchesPico(firmware);
            }
        }
    }

    private static void assertHeaderMatchesPico(Firmware other) throws IOException {
        byte[] pico = Files.readAllBytes(PICO.protocolHeader());
        byte[] esp = Files.readAllBytes(other.protocolHeader());
        if (Arrays.equals(pico, esp)) {
            return;
        }

        /*
           A whole diff would bury the difference in a hundred lines of identical
           header, so the report names the first line that differs. Reading the
           files as lines also separates the two ways they can differ: different
           content, or the same content with different line endings.
        */
        List<String> picoLines = Files.readAllLines(PICO.protocolHeader());
        List<String> espLines = Files.readAllLines(other.protocolHeader());

        for (int i = 0; i < Math.min(picoLines.size(), espLines.size()); i++) {
            if (!picoLines.get(i).equals(espLines.get(i))) {
                fail("""
                        Protocol.h has diverged at line %d.
                          %s: %s
                          %s: %s
                        The two files must stay byte identical: copy one over the other."""
                        .formatted(i + 1,
                                PICO.label(), picoLines.get(i),
                                other.label(), espLines.get(i)));
            }
        }
        if (picoLines.size() != espLines.size()) {
            fail("Protocol.h has diverged: %s has %d lines, %s has %d."
                    .formatted(PICO.label(), picoLines.size(), other.label(), espLines.size()));
        }
        /*
           Equal line by line but not byte by byte: CRLF against LF, or a missing
           newline at the end of one file. Harmless to the compiler, which is why
           it needs naming — otherwise this test fails with nothing to see.
        */
        fail("Protocol.h in %s: the lines match but the bytes differ — "
                .formatted(other.label())
                + "line endings or a missing final newline.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedValues")
    void sharedValueIsTheSameInBothSketches(Rule rule) {
        String inPico = valueOf(rule, PICO);
        String inEsp = valueOf(rule, ESP);
        assertEquals(inPico, inEsp,
                () -> "%s differs between the two firmwares".formatted(rule.label()));
    }

    /**
     * The safety net: every other constant the two sketches happen to share, so
     * adding one to both files puts it under this test without anyone editing
     * {@link #sharedValues()}. Names defined in only one sketch are skipped —
     * the variants decode different field sets, so that is expected.
     */
    @Test
    void everyOtherSharedConstantHasTheSameValue() {
        Map<String, String> inPico = constants(PICO);
        Map<String, String> inEsp = constants(ESP);

        var shared = new TreeSet<>(inPico.keySet());
        shared.retainAll(inEsp.keySet());

        var diverged = new ArrayList<String>();
        shared.forEach(name -> {
            if (!inPico.get(name).equals(inEsp.get(name))) {
                diverged.add("  %s: %s=%s but %s=%s".formatted(name,
                        PICO.label(), inPico.get(name), ESP.label(), inEsp.get(name)));
            }
        });

        if (!diverged.isEmpty()) {
            fail("Constants defined in both sketches with different values:\n"
                    + String.join("\n", diverged));
        }
    }

    private static String valueOf(Rule rule, Firmware firmware) {
        return rule.extract(firmware.sketchSource()).orElseGet(() -> fail("""
                %s not found in %s. The pattern %s matched nothing — either the decoding \
                was rewritten, in which case update the pattern, or it was removed, in \
                which case the two firmwares no longer agree."""
                .formatted(rule.label(), firmware.sketch().getFileName(), rule.pattern())));
    }

    private static Map<String, String> constants(Firmware firmware) {
        var found = new LinkedHashMap<String, String>();
        Matcher matcher = DECLARED_CONSTANT.matcher(firmware.sketchSource());
        while (matcher.find()) {
            found.put(matcher.group(1), normalize(matcher.group(2)));
        }
        return found;
    }

    /**
     * A firmware directory, found by walking up from the working directory: the
     * tests run in the server module, and the sketches are its siblings.
     */
    private static Path firmwareDirectory(String name) {
        for (Path candidate = Path.of("").toAbsolutePath();
                candidate != null; candidate = candidate.getParent()) {
            Path firmware = candidate.resolve(name);
            if (Files.isDirectory(firmware)) {
                return firmware;
            }
        }
        throw new IllegalStateException("""
                No %s directory at or above %s. The firmware sources are part of this \
                repository, so this test needs a full checkout."""
                .formatted(name, Path.of("").toAbsolutePath()));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    /** Formatting differences are not divergences, so they are collapsed away. */
    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }
}
