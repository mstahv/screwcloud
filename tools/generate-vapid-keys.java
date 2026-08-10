/*
   Generates a VAPID key pair for web push notifications.

   Run it with the JDK you already have — no Node, no npx:

       java tools/generate-vapid-keys.java

   The keys are an EC P-256 pair in the base64url form the Web Push protocol
   wants: the public key as the 65-byte uncompressed point the browser expects as
   applicationServerKey, the private key as the raw 32-byte scalar.

   Keep the private key out of version control. It is what proves to the browsers'
   push services that a notification comes from your server.
*/
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

public class GenerateVapidKeys {

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();

        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;  // uncompressed point marker
        System.arraycopy(toFixed32(publicKey.getW().getAffineX()), 0, uncompressed, 1, 32);
        System.arraycopy(toFixed32(publicKey.getW().getAffineY()), 0, uncompressed, 33, 32);

        byte[] privateKey = toFixed32(((ECPrivateKey) pair.getPrivate()).getS());

        Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
        System.out.println("VAPID_PUBLIC_KEY=" + base64url.encodeToString(uncompressed));
        System.out.println("VAPID_PRIVATE_KEY=" + base64url.encodeToString(privateKey));
        System.out.println("VAPID_SUBJECT=mailto:you@example.com");
    }

    /*
       BigInteger.toByteArray() gives the shortest two's complement form, which is
       33 bytes when the high bit is set and fewer when there are leading zeros.
       The protocol wants exactly 32, so it is trimmed or left-padded.
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
