package fi.mstahv.sensorhub.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A WiFi network name.
 *
 * <p>802.11 gives the SSID element 32 <b>bytes</b>, not 32 characters, and that
 * is the whole reason this is not a {@code @Size}. A network called
 * {@code Mökkiverkko} is eleven characters and thirteen bytes once encoded, and
 * a length checked in characters would accept names a device cannot hold.
 *
 * <p>Null and blank are left to {@code @NotBlank}, as the package documents.
 */
@Documented
@Constraint(validatedBy = SsidValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Ssid {

    /** The 802.11 SSID element's size, in bytes. */
    int MAX_BYTES = 32;

    String message() default "A network name is at most 32 bytes long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
