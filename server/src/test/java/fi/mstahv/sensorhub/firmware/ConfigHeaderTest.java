package fi.mstahv.sensorhub.firmware;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator is what stands between a text field and a compiler, so most of
 * this is about what must <i>not</i> come out of it.
 *
 * <p>The template is the firmware's real {@code config.h.example}, read from the
 * repository rather than pasted here. That makes these tests the drift detector
 * the class documents: rename a declaration in the firmware and they fail, which
 * is the point.
 *
 * <p>Most of what is checked is the same for both boards, and is checked against
 * the Pico's template. The ESP32's is put through the same generator at the end,
 * because it is the second template and the one somebody editing the first will
 * not think of.
 */
class ConfigHeaderTest {

    private static Path template(Board board) {
        return Path.of("..", board.sketch(), "config.h.example");
    }

    private static String template() throws IOException {
        return Files.readString(template(Board.PICO_2_W));
    }

    private static String generate(FirmwareRequest request) throws IOException {
        StringWriter out = new StringWriter();
        ConfigHeader.write(request, Files.readString(template(request.board())), out);
        return out.toString();
    }

    private static FirmwareRequest request(String ssid, String password) {
        return new FirmwareRequest(Board.PICO_2_W, "ABCD", ssid, password, 5, FirmwareTransport.AUTOMATIC, false);
    }

    @ParameterizedTest
    @EnumSource(Board.class)
    void theRepositoryTemplateStillHasEverythingThisNeeds(Board board) throws IOException {
        // Fails loudly if the firmware renames a setting. That is the contract.
        generate(new FirmwareRequest(board, "ABCD", "net", "password", 5,
                FirmwareTransport.AUTOMATIC, false));
    }

    @Test
    void theEsp32GetsTheSameValuesAndNoRadioQuestion() throws IOException {
        String header = generate(new FirmwareRequest(Board.ESP32_S3, "abcd", "Wifi", "hunter22",
                15, FirmwareTransport.AUTOMATIC, false));

        assertTrue(header.contains(
                "static const char DEVICE_ID[5] = { 0x41, 0x42, 0x43, 0x44, 0x00 };"), header);
        assertTrue(header.contains(
                "static const char WIFI_SSID[] = { 0x57, 0x69, 0x66, 0x69, 0x00 };"), header);
        assertTrue(header.contains("static const char WIFI_PASSWORD[] = "
                + "{ 0x68, 0x75, 0x6E, 0x74, 0x65, 0x72, 0x32, 0x32, 0x00 };"), header);
        assertTrue(header.contains(
                "static const unsigned long SEND_INTERVAL_MS = 15UL * 60UL * 1000UL;"), header);
        /*
           The ESP32 has one radio and its template has no TRANSPORT_* lines.
           The generator must not have invented any, and — the case this test is
           really for — must not have refused the template for lacking them.
        */
        assertFalse(header.contains("TRANSPORT_"), header);
    }

    @Test
    void theEsp32SleepsOnlyWhenAsked() throws IOException {
        String awake = generate(new FirmwareRequest(Board.ESP32_C3, "ABCD", "net", "password", 5,
                FirmwareTransport.WIFI, false));
        String asleep = generate(new FirmwareRequest(Board.ESP32_C3, "ABCD", "net", "password", 5,
                FirmwareTransport.WIFI, true));

        assertTrue(awake.contains("\n//#define SLEEP_BETWEEN_SENDS\n"), awake);
        assertFalse(awake.contains("\n#define SLEEP_BETWEEN_SENDS\n"), awake);
        assertTrue(asleep.contains("\n#define SLEEP_BETWEEN_SENDS\n"), asleep);
        assertFalse(asleep.contains("\n//#define SLEEP_BETWEEN_SENDS\n"), asleep);
    }

    @Test
    void thePicoIsNotAskedToSleep() throws IOException {
        // Its template has no such line, and asking for one would trip the drift detector.
        String header = generate(new FirmwareRequest(Board.PICO_2_W, "ABCD", "net", "password", 5,
                FirmwareTransport.AUTOMATIC, true));

        assertFalse(header.contains("SLEEP_BETWEEN_SENDS"), header);
    }

    @Test
    void valuesBecomeByteArraysRatherThanStringLiterals() throws IOException {
        String header = generate(request("Wifi", "hunter22"));

        assertTrue(header.contains(
                "static const char WIFI_SSID[] = { 0x57, 0x69, 0x66, 0x69, 0x00 };"), header);
        assertTrue(header.contains("static const char WIFI_PASSWORD[] = "
                + "{ 0x68, 0x75, 0x6E, 0x74, 0x65, 0x72, 0x32, 0x32, 0x00 };"), header);
    }

    @Test
    void theDeviceIdIsUpperCasedAndStripped() throws IOException {
        String header = generate(
                new FirmwareRequest(Board.PICO_2_W, "  topi ", "net", "password", 5, FirmwareTransport.AUTOMATIC, false));

        // T O P I, then the terminator.
        assertTrue(header.contains(
                "static const char DEVICE_ID[5] = { 0x54, 0x4F, 0x50, 0x49, 0x00 };"), header);
    }

    /**
     * The reason the byte arrays exist. Every one of these would end a C string
     * literal, open a comment, or start a preprocessor directive; none of them
     * can reach the output as anything but a number.
     */
    @Test
    void nothingATypistCanEnterBecomesSyntax() throws IOException {
        String hostile = "\"; system(\"rm -rf /\"); //";
        String header = generate(request("net", hostile));

        String generated = lineWith(header, "static const char WIFI_PASSWORD[]");
        assertFalse(generated.contains("system"), generated);
        assertFalse(generated.contains("\""), generated);
        assertTrue(generated.matches("static const char WIFI_PASSWORD\\[] = \\{( 0x[0-9A-F]{2},)+ 0x00 };"),
                generated);
    }

    @Test
    void aPreprocessorDirectiveIsJustBytesToo() throws IOException {
        String header = generate(request("net", "#include \"/etc/passwd\""));

        assertFalse(header.contains("/etc/passwd"), header);
        assertEquals(0, header.lines().filter(line -> line.startsWith("#include")).count(),
                "an #include the user typed must not have become one the compiler would obey");
    }

    @Test
    void multiByteCharactersAreCountedAsTheRadioCountsThem() throws IOException {
        // "Mökki" is five characters and six bytes: ö is two.
        String header = generate(request("Mökki", "password"));

        assertTrue(header.contains(
                "static const char WIFI_SSID[] = { 0x4D, 0xC3, 0xB6, 0x6B, 0x6B, 0x69, 0x00 };"),
                header);
    }

    @Test
    void anOpenNetworkGetsAnEmptyPassword() throws IOException {
        String header = generate(request("net", ""));

        assertTrue(header.contains("static const char WIFI_PASSWORD[] = { 0x00 };"), header);
    }

    @Test
    void aValueTooLongForTheFirmwareIsRefusedHereAsWell() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigHeader.byteArray("x".repeat(33), 32));
    }

    @Test
    void theSendIntervalIsWrittenInMinutes() throws IOException {
        String header = generate(
                new FirmwareRequest(Board.PICO_2_W, "ABCD", "net", "password", 15, FirmwareTransport.AUTOMATIC, false));

        assertTrue(header.contains(
                "static const unsigned long SEND_INTERVAL_MS = 15UL * 60UL * 1000UL;"), header);
    }

    @Test
    void exactlyOneTransportIsDefinedWhicheverWasAsked() throws IOException {
        for (FirmwareTransport transport : FirmwareTransport.values()) {
            String header = generate(
                    new FirmwareRequest(Board.PICO_2_W, "ABCD", "net", "password", 5, transport, false));

            assertTrue(header.contains("\n#define " + transport.macro() + "\n"),
                    transport + " should be the one defined");
            for (FirmwareTransport other : FirmwareTransport.values()) {
                if (other != transport) {
                    assertTrue(header.contains("\n//#define " + other.macro() + "\n"),
                            other + " should be commented out for " + transport);
                }
            }
        }
    }

    @Test
    void aTemplateThatNoLongerDeclaresASettingStopsTheBuild() throws IOException {
        String mangled = template().replace("static const char WIFI_SSID[]",
                "static const char WIFI_NETWORK[]");

        StringWriter out = new StringWriter();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ConfigHeader.write(request("net", "password"), mangled, out));
        assertTrue(thrown.getMessage().contains("WIFI_SSID"), thrown.getMessage());
    }

    private static String lineWith(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).findFirst().orElseThrow();
    }

}
