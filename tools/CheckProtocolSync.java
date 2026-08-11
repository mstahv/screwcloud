///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS io.github.java-diff-utils:java-diff-utils:4.15

/*
   Verifies that the two firmware variants agree on the wire format.

   Run it as a script, from anywhere in the repository:

       tools/CheckProtocolSync.java

   That needs jbang (sdk install jbang, or brew install jbangdev/tap/jbang),
   which the shebang line above invokes. jbang is here for one reason: the //DEPS
   line below, which fetches the library that renders the unified diff. The JDK's
   own single-file launcher cannot resolve dependencies, so plain
   "java tools/CheckProtocolSync.java" fails at the import.

   Protocol.h is duplicated rather than shared because the Arduino build does not
   reliably resolve includes that reach outside a sketch folder — arduino-cli
   copies the sketch directory into its build path, so a "../shared/Protocol.h"
   include can break depending on the toolchain version. A byte-identical copy
   plus this check is more robust than an include that might silently stop
   working.

   The Ruuvi Data Format 5 decoding is checked too. That format is externally
   fixed and will not change, but a typo in one copy would produce silently wrong
   temperatures from one board only — exactly the kind of bug that takes days to
   notice.

   Only what both variants must share is checked. The ESP32 variant deliberately
   decodes fewer fields (no pressure, acceleration or battery), so the sketches
   are not expected to match line for line.

   Every check has the same shape: pull one value out of each sketch with a
   regular expression and compare the two against each other. The shell version
   this replaces did something subtly different — it grepped for the expected
   text, "temperature = rawTemperature * 0.005f", in both files. That worked, but
   it made the checker a third copy of the wire format: changing the scaling in
   both sketches broke the check until someone edited the checker too, and
   reformatting one line broke it while nothing had actually diverged. Comparing
   the files against each other holds no opinion about what the values should be,
   only that they match, and a mismatch is reported with both values rather than
   as a missing string.
*/

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

public class CheckProtocolSync {

    /**
     * One firmware variant. The sketch is named after its directory because the
     * Arduino toolchain requires it, which is why one field is enough.
     */
    record Firmware(String label, Path directory) {

        Path protocolHeader() {
            return directory.resolve("Protocol.h");
        }

        Path sketch() {
            return directory.resolve(directory.getFileName() + ".ino");
        }

        String sketchSource() throws IOException {
            return Files.readString(sketch());
        }
    }

    /**
     * One value that must be the same in both sketches, and the pattern that
     * finds it. The pattern must have exactly one capturing group: what it
     * captures is what gets compared.
     */
    record Rule(String label, Pattern pattern) {

        Rule(String label, String regex) {
            this(label, Pattern.compile(regex));
        }

        /** The captured value with its whitespace collapsed, or empty if absent. */
        Optional<String> extract(String source) {
            Matcher matcher = pattern.matcher(source);
            return matcher.find() ? Optional.of(normalize(matcher.group(1))) : Optional.empty();
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
    private static final List<Rule> RULES = List.of(
            new Rule("RUUVI_COMPANY_ID", "RUUVI_COMPANY_ID\\s*=\\s*([^;]+);"),
            new Rule("RUUVI_FORMAT_5", "RUUVI_FORMAT_5(?!_)\\s*=\\s*([^;]+);"),
            new Rule("RUUVI_FORMAT_5_LEN", "RUUVI_FORMAT_5_LEN\\s*=\\s*([^;]+);"),
            new Rule("temperature scaling", "temperature\\s*=\\s*(rawTemperature\\s*\\*[^;]+);"),
            new Rule("humidity scaling", "humidity\\s*=\\s*(rawHumidity\\s*\\*[^;]+);"),
            new Rule("temperature sentinel", "rawTemperature\\s*!=\\s*(.+?)\\)\\s*\\{"),
            new Rule("humidity sentinel", "rawHumidity\\s*!=\\s*(.+?)\\)\\s*\\{"),
            new Rule("MAC offset", "memcpy\\(mac,\\s*&data\\[(\\d+)\\]"));

    /**
     * Every {@code static const} and {@code #define} in a sketch, so that a
     * constant nobody thought to list above is still compared. Names defined in
     * only one sketch are skipped: the variants decode different field sets, so
     * that is expected rather than a divergence.
     */
    private static final Pattern DECLARED_CONSTANT = Pattern.compile(
            "^\\s*(?:static\\s+const\\s+\\w+|#define)\\s+(\\w+)\\s*=?\\s*([^;\\n]+?);?\\s*(?://.*)?$",
            Pattern.MULTILINE);

    public static void main(String[] args) throws IOException {
        /*
           Files are read as UTF-8, but System.out takes its encoding from the
           console and falls back to the platform's when the output is piped —
           ASCII on a machine with no LANG set. Protocol.h's comments are full of
           en dashes, so without this the diff reports differences as question
           marks. Set here rather than as a JVM option so it holds however the
           script is launched.
        */
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8));

        Path root = repositoryRoot();
        Firmware pico = new Firmware("pico", root.resolve("temperature-reader"));
        Firmware esp = new Firmware("esp32", root.resolve("esp32-s3-reader"));

        // Every check runs even when an earlier one fails: one report beats three runs.
        boolean ok = checkHeaders(pico, esp);
        ok &= checkDecodingRules(pico, esp);
        ok &= checkRemainingConstants(pico, esp);

        System.out.println();
        System.out.println(ok
                ? "All checks passed."
                : "Checks failed: the variants no longer agree on the format.");
        System.exit(ok ? 0 : 1);
    }

    private static boolean checkHeaders(Firmware pico, Firmware esp) throws IOException {
        System.out.println("== Protocol.h: the wire format to the server ==");

        byte[] picoBytes = Files.readAllBytes(pico.protocolHeader());
        byte[] espBytes = Files.readAllBytes(esp.protocolHeader());
        if (Arrays.equals(picoBytes, espBytes)) {
            System.out.println("OK: byte identical");
            return true;
        }

        List<String> picoLines = Files.readAllLines(pico.protocolHeader());
        List<String> espLines = Files.readAllLines(esp.protocolHeader());
        Patch<String> patch = DiffUtils.diff(picoLines, espLines);
        if (patch.getDeltas().isEmpty()) {
            /*
               The lines are equal but the bytes are not: CRLF against LF, or a
               missing newline at the end of one file. Harmless to the compiler,
               but it would make this check fail forever with an empty diff, so
               it is worth naming.
            */
            System.out.println("FAIL: the lines match but the bytes differ — "
                    + "line endings or a missing final newline");
            return false;
        }

        UnifiedDiffUtils.generateUnifiedDiff(
                        pico.protocolHeader().toString(), esp.protocolHeader().toString(),
                        picoLines, patch, 3)
                .forEach(System.out::println);
        System.out.println("FAIL: the wire format definitions have diverged (see the diff above)");
        return false;
    }

    private static boolean checkDecodingRules(Firmware pico, Firmware esp) throws IOException {
        System.out.println();
        System.out.println("== Ruuvi Data Format 5: shared values ==");

        String picoSource = pico.sketchSource();
        String espSource = esp.sketchSource();
        boolean ok = true;

        for (Rule rule : RULES) {
            Optional<String> inPico = rule.extract(picoSource);
            Optional<String> inEsp = rule.extract(espSource);

            if (inPico.isEmpty() || inEsp.isEmpty()) {
                System.out.printf("FAIL  %-22s not found in %s%n", rule.label(),
                        inPico.isEmpty() ? (inEsp.isEmpty() ? "either sketch" : pico.label())
                                : esp.label());
                ok = false;
            } else if (inPico.equals(inEsp)) {
                System.out.printf("OK    %-22s %s%n", rule.label(), inPico.get());
            } else {
                System.out.printf("FAIL  %-22s %s=%s but %s=%s%n", rule.label(),
                        pico.label(), inPico.get(), esp.label(), inEsp.get());
                ok = false;
            }
        }
        return ok;
    }

    /**
     * The safety net: compares every other constant the two sketches happen to
     * share, so adding one to both files puts it under this check without
     * anyone editing {@link #RULES}.
     */
    private static boolean checkRemainingConstants(Firmware pico, Firmware esp) throws IOException {
        System.out.println();
        System.out.println("== Other constants defined in both sketches ==");

        Map<String, String> inPico = constants(pico);
        Map<String, String> inEsp = constants(esp);
        List<String> alreadyChecked = RULES.stream().map(Rule::label).toList();

        var shared = new TreeSet<>(inPico.keySet());
        shared.retainAll(inEsp.keySet());
        shared.removeAll(alreadyChecked);

        var diverged = new ArrayList<String>();
        for (String name : shared) {
            if (!inPico.get(name).equals(inEsp.get(name))) {
                diverged.add("FAIL  %-22s %s=%s but %s=%s".formatted(name,
                        pico.label(), inPico.get(name), esp.label(), inEsp.get(name)));
            }
        }

        if (diverged.isEmpty()) {
            System.out.printf("OK: %d shared, all values agree%n", shared.size());
            return true;
        }
        diverged.forEach(System.out::println);
        return false;
    }

    private static Map<String, String> constants(Firmware firmware) throws IOException {
        var found = new LinkedHashMap<String, String>();
        Matcher matcher = DECLARED_CONSTANT.matcher(firmware.sketchSource());
        while (matcher.find()) {
            found.put(matcher.group(1), normalize(matcher.group(2)));
        }
        return found;
    }

    /**
     * Where the firmware directories are, found by walking up from the working
     * directory. The old shell version had to be run from the repository root;
     * this one does not care where it is started.
     */
    private static Path repositoryRoot() {
        for (Path candidate = Path.of("").toAbsolutePath();
                candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("temperature-reader"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No temperature-reader directory above " + Path.of("").toAbsolutePath()
                        + " — run this inside the repository.");
    }

    /** Formatting differences are not divergences, so they are collapsed away. */
    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }
}
