package fi.mstahv.sensorhub.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A WPA2 passphrase, or nothing at all for an open network.
 *
 * <p>Empty is valid and deliberately so. A network without a password is a
 * network without a password, and refusing to build for one would only make
 * somebody invent a value the access point has never heard of.
 *
 * <p>Given a value, the range is WPA2's own: 8 to 63 characters. Both ends are
 * real limits rather than taste — below eight the standard does not allow it,
 * and at 64 the field means something else entirely, a raw hexadecimal PSK,
 * which this does not support and which nobody types from memory.
 *
 * <p><b>The character set is deliberately not restricted.</b> A passphrase may
 * contain quotes and backslashes, and a rule strict enough to make a quoted C
 * string literal safe would reject passwords people genuinely have. Safety comes
 * from {@code ConfigHeader} emitting the value as bytes rather than as source
 * text, which leaves this constraint free to be about what the standard allows.
 */
@Documented
@Constraint(validatedBy = WifiPassphraseValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface WifiPassphrase {

    int MIN_LENGTH = 8;

    int MAX_LENGTH = 63;

    String message() default "A WiFi password is 8 to 63 characters, or empty for an open network";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
