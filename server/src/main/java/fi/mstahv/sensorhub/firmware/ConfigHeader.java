package fi.mstahv.sensorhub.firmware;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.mstahv.sensorhub.validation.Ssid;
import fi.mstahv.sensorhub.validation.WifiPassphrase;

/**
 * Writes the config.h a build compiles against.
 *
 * <h2>It edits the template rather than replacing it</h2>
 *
 * <p>The input is the {@code config.h.example} out of the very checkout being
 * built, and this changes the handful of lines that differ per device. A second
 * copy of the template kept here would be a copy that drifts: the firmware grows
 * a setting, nobody remembers this file, and devices are built with a config.h
 * that is missing it. Taking the template from the source means the two cannot
 * disagree, because they are the same file.
 *
 * <p>Which makes {@link IllegalStateException} below the drift detector. Every
 * substitution insists on matching exactly once, so renaming {@code WIFI_SSID}
 * in the firmware stops the server building rather than quietly producing
 * devices that join no network. A loud failure on a server beats a silent one at
 * a summer cottage.
 *
 * <h2>Values go in as bytes, never as source text</h2>
 *
 * <p>This is the whole security argument, and it is worth one careful look
 * during review. The server takes a WiFi password from a text field and puts it
 * into a file a compiler then reads. Written the obvious way — interpolating it
 * into a quoted C string — that is remote code execution against the build host,
 * and the preprocessor makes it worse than it first looks: a value containing
 * {@code #include "/etc/passwd"} produces a compile error whose text contains the
 * file.
 *
 * <p>Escaping into a literal would work and would depend on getting the escaping
 * right, forever, against a value that may legitimately contain quotes and
 * backslashes. So no literal is produced. {@link #byteArray} emits hexadecimal
 * digits, commas and braces and nothing else, which means there is no syntax in
 * the output for an input byte to reach:
 *
 * <pre>{@code
 * static const char WIFI_PASSWORD[] = { 0x68, 0x75, 0x6E, 0x74, 0x65, 0x72, 0x00 };
 * }</pre>
 *
 * <p>Same type and same meaning to the sketch. Injection becomes impossible by
 * construction rather than by care, and the form's constraints are then free to
 * be about what a reader typed wrong rather than about what a compiler might do.
 */
public final class ConfigHeader {

    private static final Pattern DEVICE_ID =
            Pattern.compile("^static const char DEVICE_ID\\[5] = .*;$", Pattern.MULTILINE);
    private static final Pattern WIFI_SSID =
            Pattern.compile("^static const char WIFI_SSID\\[] = .*;$", Pattern.MULTILINE);
    private static final Pattern WIFI_PASSWORD =
            Pattern.compile("^static const char WIFI_PASSWORD\\[] = .*;$", Pattern.MULTILINE);
    private static final Pattern SEND_INTERVAL =
            Pattern.compile("^static const unsigned long SEND_INTERVAL_MS = .*;$", Pattern.MULTILINE);

    /** The ESP32's sleep switch, live or commented out — whichever the template ships with. */
    static final String SLEEP_MACRO = "LIGHT_SLEEP_BETWEEN_SENDS";
    private static final Pattern SLEEP =
            Pattern.compile("^(?://)?#define " + SLEEP_MACRO + "[ \t]*$", Pattern.MULTILINE);

    private ConfigHeader() {
    }

    /**
     * Writes the configured header.
     *
     * <p>Into a {@link Writer} rather than returning a string, so the caller can
     * send it straight at a file or a process without a copy in between.
     *
     * @param request what to configure it for
     * @param template the {@code config.h.example} of the checkout being built
     * @param out where the result goes
     * @throws IllegalStateException if the template no longer contains a
     *         declaration this needs to change — see the class notes
     */
    public static void write(FirmwareRequest request, String template, Writer out)
            throws IOException {
        String result = template;
        result = replaceOnce(result, DEVICE_ID, "DEVICE_ID",
                "static const char DEVICE_ID[5] = " + byteArray(request.deviceId(), 4) + ";");
        result = replaceOnce(result, WIFI_SSID, "WIFI_SSID",
                "static const char WIFI_SSID[] = " + byteArray(request.ssid(), Ssid.MAX_BYTES) + ";");
        result = replaceOnce(result, WIFI_PASSWORD, "WIFI_PASSWORD",
                "static const char WIFI_PASSWORD[] = "
                        + byteArray(request.password(), WifiPassphrase.MAX_LENGTH) + ";");
        result = replaceOnce(result, SEND_INTERVAL, "SEND_INTERVAL_MS",
                "static const unsigned long SEND_INTERVAL_MS = %dUL * 60UL * 1000UL;"
                        .formatted(request.sendIntervalMinutes()));
        /*
           Only where the template asks. The ESP32 sketch has one radio and no
           TRANSPORT_* lines, and looking for them there would trip the drift
           detector over a difference that is not drift.
        */
        if (request.board().choosesTransport()) {
            result = selectTransport(result, request.transport());
        }
        /*
           The same shape as the transport choice: the template's line is rewritten
           live or commented out, whichever it shipped as, so the result does not
           depend on the default the firmware happens to have that month.
        */
        if (request.board().offersSleep()) {
            result = replaceOnce(result, SLEEP, SLEEP_MACRO,
                    (request.sleepBetweenSends() ? "" : "//") + "#define " + SLEEP_MACRO);
        }
        out.write(result);
    }

    /**
     * A C initialiser holding the value's UTF-8 bytes and a terminator.
     *
     * <p>The length check belongs here and not only in the form, because this is
     * the last place that can refuse. The form's constraints are about telling a
     * reader they mistyped; this is about a value never being longer than the
     * array the firmware declares, whatever route it arrived by.
     */
    static String byteArray(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "Value is %d bytes, which is more than the %d the firmware has room for"
                            .formatted(bytes.length, maxBytes));
        }
        StringBuilder initialiser = new StringBuilder("{ ");
        for (byte b : bytes) {
            initialiser.append("0x%02X, ".formatted(b));
        }
        return initialiser.append("0x00 }").toString();
    }

    /**
     * Comments out every transport macro and uncomments the chosen one, so the
     * result is the same whichever the template happened to ship with.
     */
    private static String selectTransport(String source, FirmwareTransport chosen) {
        String result = source;
        for (FirmwareTransport transport : FirmwareTransport.values()) {
            /*
               The slashes are optional as a pair: the template ships with one
               macro live and two commented out, and which one it happens to be
               is not this method's business.
            */
            Pattern pattern = Pattern.compile(
                    "^(?://)?#define " + transport.macro() + "[ \t]*$", Pattern.MULTILINE);
            String replacement = transport == chosen
                    ? "#define " + transport.macro()
                    : "//#define " + transport.macro();
            result = replaceOnce(result, pattern, transport.macro(), replacement);
        }
        return result;
    }

    /**
     * Counted in the template, before anything is replaced. Counting afterwards
     * would find this method's own output — a generated declaration looks exactly
     * like the one it replaced, which is rather the point of it.
     */
    private static String replaceOnce(String source, Pattern pattern, String what, String replacement) {
        Matcher matcher = pattern.matcher(source);
        long matches = matcher.results().count();
        if (matches == 0) {
            throw new IllegalStateException(
                    "config.h.example no longer declares " + what + " the way this expects. "
                            + "The firmware and the builder have drifted apart; see ConfigHeader.");
        }
        if (matches > 1) {
            throw new IllegalStateException(
                    "config.h.example declares " + what + " " + matches + " times, which this "
                            + "cannot resolve safely. See ConfigHeader.");
        }
        return pattern.matcher(source).replaceFirst(Matcher.quoteReplacement(replacement));
    }
}
