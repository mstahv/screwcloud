package fi.mstahv.sensorhub;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VAPID keys for local development, generated on first use so that
 * {@link TestServer} has working notifications with nothing to set up.
 *
 * <p>Without this the notification UI is disabled on a developer's machine —
 * correct for a server with no keys, but no use when that UI is what you are
 * working on. Generating a pair beats showing the switch as a decoration: the
 * permission prompt, the subscription round trip and the actual delivery all
 * work, because a push service does not care where a VAPID key came from. It only
 * checks that a notification is signed by the key its subscription was created
 * with.
 *
 * <p>Which is exactly why the pair is <b>cached in a file</b> rather than
 * generated per start. A new pair on every restart would invalidate every
 * subscription a browser had already made, and those would then fail forever with
 * nothing on screen to explain it.
 *
 * <p>Real keys always win: if {@code VAPID_PUBLIC_KEY} and
 * {@code VAPID_PRIVATE_KEY} are set, this does nothing. That check matters
 * because these are applied as system properties, which outrank environment
 * variables in Spring's order of precedence — without it, a developer's real keys
 * would be silently replaced by throwaway ones.
 */
final class DevVapidKeys {

    private static final Logger log = LoggerFactory.getLogger(DevVapidKeys.class);

    private static final String PUBLIC_PROPERTY = "sensorhub.webpush.public-key";
    private static final String PRIVATE_PROPERTY = "sensorhub.webpush.private-key";
    private static final String SUBJECT_PROPERTY = "sensorhub.webpush.subject";

    /*
       A subject has to be a mailto: or https: URL — Apple's push service rejects
       notifications without one. Nothing ever mails it, so a placeholder will do.
    */
    private static final String DEV_SUBJECT = "mailto:dev@example.com";

    private DevVapidKeys() {
    }

    /**
     * Generates or loads a development key pair and applies it, unless real keys
     * are already configured.
     */
    static void applyUnlessConfigured() {
        apply(defaultLocation());
    }

    /**
     * @param file where the development pair is cached
     * @return true if development keys were applied, false if real ones were left
     *         alone or generation failed
     */
    static boolean apply(Path file) {
        if (alreadyConfigured()) {
            log.info("Using the VAPID keys from the environment");
            return false;
        }
        try {
            Properties keys = loadOrCreate(file);
            System.setProperty(PUBLIC_PROPERTY, keys.getProperty("public"));
            System.setProperty(PRIVATE_PROPERTY, keys.getProperty("private"));
            System.setProperty(SUBJECT_PROPERTY, DEV_SUBJECT);
            log.info("Using development VAPID keys from {} — notifications work locally, "
                    + "but generate a real pair for a deployment", file);
            return true;
        } catch (IOException | GeneralSecurityException e) {
            /*
               Not fatal: the application runs perfectly well without
               notifications, and failing a development start over a file that
               could not be written would be out of proportion.
            */
            log.warn("Could not prepare development VAPID keys ({}), "
                    + "notifications stay disabled", e.getMessage());
            return false;
        }
    }

    static Path defaultLocation() {
        return Path.of(System.getProperty("user.home"), ".screwcloud", "vapid-dev.properties");
    }

    private static boolean alreadyConfigured() {
        return isSet(System.getenv("VAPID_PUBLIC_KEY")) && isSet(System.getenv("VAPID_PRIVATE_KEY"))
                || isSet(System.getProperty(PUBLIC_PROPERTY));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static Properties loadOrCreate(Path file) throws IOException, GeneralSecurityException {
        if (Files.exists(file)) {
            Properties existing = new Properties();
            try (var in = Files.newInputStream(file)) {
                existing.load(in);
            }
            if (isSet(existing.getProperty("public")) && isSet(existing.getProperty("private"))) {
                return existing;
            }
            // A truncated file is more useful replaced than reported.
        }
        return create(file);
    }

    private static Properties create(Path file) throws IOException, GeneralSecurityException {
        Properties keys = generate();

        Files.createDirectories(file.getParent());
        try (var out = Files.newOutputStream(file)) {
            keys.store(out, "Development VAPID keys for ScrewCloud's TestServer. "
                    + "Not for a deployment: generate a pair with tools/generate-vapid-keys.java.");
        }
        /*
           The private key is a credential, throwaway or not. Best effort only:
           POSIX permissions are unsupported on some filesystems, and a startup
           should not fail over them.
        */
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("Could not restrict the permissions of {}", file);
        }
        return keys;
    }

    /*
       An EC P-256 pair in the base64url form the Web Push protocol wants: the
       public key as the 65-byte uncompressed point a browser expects as
       applicationServerKey, the private key as the raw 32-byte scalar.

       These few lines also exist in tools/generate-vapid-keys.java, deliberately:
       that script has to run on a bare JDK before anything is built, so it cannot
       call in here.
    */
    private static Properties generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();

        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;  // uncompressed point marker
        System.arraycopy(toFixed32(publicKey.getW().getAffineX()), 0, uncompressed, 1, 32);
        System.arraycopy(toFixed32(publicKey.getW().getAffineY()), 0, uncompressed, 33, 32);

        Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
        Properties keys = new Properties();
        keys.setProperty("public", base64url.encodeToString(uncompressed));
        keys.setProperty("private", base64url.encodeToString(
                toFixed32(((ECPrivateKey) pair.getPrivate()).getS())));
        return keys;
    }

    /*
       BigInteger.toByteArray() gives the shortest two's complement form: 33 bytes
       when the high bit is set, fewer when there are leading zeros. The protocol
       wants exactly 32.
    */
    private static byte[] toFixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        if (raw.length >= 32) {
            System.arraycopy(raw, raw.length - 32, fixed, 0, 32);
        } else {
            System.arraycopy(raw, 0, fixed, 32 - raw.length, raw.length);
        }
        return fixed;
    }
}
