package fi.mstahv.sensorhub.firmware;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the form refuses, and — more interestingly — what it must not.
 *
 * <p>The temptation with a field that ends up in a compiler is to lock it down
 * until nothing surprising can get through. That would be the wrong instinct
 * here: safety comes from {@link ConfigHeader} emitting bytes, which leaves
 * these rules free to be about what a reader mistyped. A constraint that
 * rejected a password somebody genuinely has would be a bug.
 */
class FirmwareRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    private static Set<ConstraintViolation<FirmwareRequest>> violations(FirmwareRequest request) {
        return validator.validate(request);
    }

    private static FirmwareRequest valid() {
        return new FirmwareRequest(Board.PICO_2_W, "AB2C", "Mökkiverkko", "hunter22", 5,
                FirmwareTransport.AUTOMATIC);
    }

    @Test
    void anOrdinaryRequestPasses() {
        assertTrue(violations(valid()).isEmpty());
    }

    @Test
    void theNetworkNameIsCountedInBytesNotCharacters() {
        // 32 characters, but ö is two bytes each: 40 bytes, and the radio has 32.
        String tooLong = "ö".repeat(16) + "a".repeat(8);
        assertEquals(40, tooLong.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        assertFalse(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", tooLong, "hunter22", 5,
                FirmwareTransport.AUTOMATIC)).isEmpty(),
                "a name that fits in characters but not in bytes must be refused");

        // And 32 plain ASCII characters are 32 bytes, which fits exactly.
        assertTrue(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", "a".repeat(32), "hunter22", 5,
                FirmwareTransport.AUTOMATIC)).isEmpty());
    }

    @Test
    void anOpenNetworkNeedsNoPassword() {
        assertTrue(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", "Open", "", 5,
                FirmwareTransport.AUTOMATIC)).isEmpty());
    }

    @Test
    void aPasswordShorterThanWpaAllowsIsATypoAndIsSaidSo() {
        assertFalse(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", "net", "short", 5,
                FirmwareTransport.AUTOMATIC)).isEmpty());
    }

    /**
     * The rule that is easy to get wrong in the safe direction. These are
     * legitimate WPA2 passphrases and every one of them would need escaping in a
     * C string literal — which is exactly why the value never becomes one.
     */
    @Test
    void awkwardButLegalPasswordsAreAccepted() {
        for (String password : new String[]{
                "he said \"no\"", "back\\slash", "semi;colon#hash", "'quoted'", "%s %d %n",
                "*/ end of comment", "a".repeat(63)}) {
            assertTrue(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", "net", password, 5,
                    FirmwareTransport.AUTOMATIC)).isEmpty(),
                    "should be a usable password: " + password);
        }
    }

    @Test
    void aPasswordPastWpaMaximumIsRefused() {
        assertFalse(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", "net", "a".repeat(64), 5,
                FirmwareTransport.AUTOMATIC)).isEmpty());
    }

    @Test
    void theSendIntervalStaysWithinItsRange() {
        assertFalse(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", "net", "hunter22", 0,
                FirmwareTransport.AUTOMATIC)).isEmpty());
        assertFalse(violations(new FirmwareRequest(Board.PICO_2_W, "AB2C", "net", "hunter22", 61,
                FirmwareTransport.AUTOMATIC)).isEmpty());
    }

    @Test
    void theDeviceIdFollowsTheSameRuleAsEverywhereElse() {
        assertFalse(violations(new FirmwareRequest(Board.PICO_2_W, "TOOLONG", "net", "hunter22", 5,
                FirmwareTransport.AUTOMATIC)).isEmpty());
        assertFalse(violations(new FirmwareRequest(Board.PICO_2_W, "A-B", "net", "hunter22", 5,
                FirmwareTransport.AUTOMATIC)).isEmpty());
    }

    @Test
    void theRequestDoesNotPrintThePassword() {
        String printed = valid().toString();

        assertFalse(printed.contains("hunter22"), printed);
        assertTrue(printed.contains("AB2C"), printed);
    }
}
