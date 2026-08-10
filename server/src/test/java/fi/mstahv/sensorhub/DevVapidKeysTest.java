package fi.mstahv.sensorhub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vaadin.flow.server.webpush.WebPush;

/**
 * No Spring context: this runs before one exists.
 */
class DevVapidKeysTest {

    private static final String PUBLIC_PROPERTY = "sensorhub.webpush.public-key";
    private static final String PRIVATE_PROPERTY = "sensorhub.webpush.private-key";
    private static final String SUBJECT_PROPERTY = "sensorhub.webpush.subject";

    @TempDir
    private Path directory;

    @BeforeEach
    @AfterEach
    void clearProperties() {
        System.clearProperty(PUBLIC_PROPERTY);
        System.clearProperty(PRIVATE_PROPERTY);
        System.clearProperty(SUBJECT_PROPERTY);
    }

    @Test
    void firstRunGeneratesAndAppliesAPair() {
        assertTrue(DevVapidKeys.apply(file()));

        assertNotNull(System.getProperty(PUBLIC_PROPERTY));
        assertNotNull(System.getProperty(PRIVATE_PROPERTY));
        assertNotNull(System.getProperty(SUBJECT_PROPERTY));
    }

    /*
       The reason the pair is cached at all: a new one on every restart would
       invalidate the subscriptions browsers had already made with the old public
       key, and they would then fail forever.
    */
    @Test
    void theSamePairIsReusedOnTheNextRun() {
        DevVapidKeys.apply(file());
        String firstPublic = System.getProperty(PUBLIC_PROPERTY);
        String firstPrivate = System.getProperty(PRIVATE_PROPERTY);
        clearProperties();

        DevVapidKeys.apply(file());

        assertEquals(firstPublic, System.getProperty(PUBLIC_PROPERTY));
        assertEquals(firstPrivate, System.getProperty(PRIVATE_PROPERTY));
    }

    /*
       System properties outrank environment variables in Spring, so without this
       check a developer's real keys would be silently replaced by throwaway ones.
    */
    @Test
    void anAlreadyConfiguredKeyIsLeftAlone() {
        System.setProperty(PUBLIC_PROPERTY, "a-real-key");

        assertFalse(DevVapidKeys.apply(file()));

        assertEquals("a-real-key", System.getProperty(PUBLIC_PROPERTY));
        assertNull(System.getProperty(PRIVATE_PROPERTY));
    }

    /*
       The shape the Web Push protocol requires: an uncompressed EC point, marker
       byte included, and a 32-byte scalar. Getting the padding wrong produces keys
       that look fine and are rejected by the push service.
    */
    @Test
    void theKeysHaveTheShapeTheProtocolRequires() {
        DevVapidKeys.apply(file());

        Base64.Decoder base64url = Base64.getUrlDecoder();
        byte[] publicKey = base64url.decode(System.getProperty(PUBLIC_PROPERTY));
        byte[] privateKey = base64url.decode(System.getProperty(PRIVATE_PROPERTY));

        assertEquals(65, publicKey.length, "The public key should be an uncompressed point");
        assertEquals(0x04, publicKey[0], "Missing the uncompressed point marker");
        assertEquals(32, privateKey.length, "The private key should be a 32 byte scalar");
    }

    /*
       The claim that matters: Vaadin accepts the pair. This is the check that a
       developer would otherwise only make by starting the application.
    */
    @Test
    void vaadinAcceptsTheGeneratedPair() {
        DevVapidKeys.apply(file());

        WebPush webPush = new WebPush(
                System.getProperty(PUBLIC_PROPERTY),
                System.getProperty(PRIVATE_PROPERTY),
                System.getProperty(SUBJECT_PROPERTY));

        assertNotNull(webPush);
    }

    @Test
    void aTruncatedFileIsReplaced() throws IOException {
        Path file = file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "public=only-half-written\n");

        assertTrue(DevVapidKeys.apply(file));

        assertNotEquals("only-half-written", System.getProperty(PUBLIC_PROPERTY));
        assertNotNull(System.getProperty(PRIVATE_PROPERTY));
    }

    private Path file() {
        return directory.resolve("nested").resolve("vapid-dev.properties");
    }
}
