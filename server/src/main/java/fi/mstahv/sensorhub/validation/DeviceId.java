package fi.mstahv.sensorhub.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A device identifier: the same four characters as {@code DEVICE_ID} in the
 * device's config.h.
 *
 * <p>Four is the protocol's own limit — the identifier travels as four ASCII
 * bytes in a fixed size packet — so anything longer cannot have come from a
 * device and cannot be sent to one either.
 *
 * <p>A validator of its own rather than {@code @Pattern}, because the value is
 * checked as it will be stored rather than as it was typed: surrounding spaces
 * are ignored and case does not matter, since the store upper-cases before saving.
 * A {@code @Pattern} would have to either reject {@code "laht"}, which a reader may
 * reasonably type, or accept mixed case into the database, where {@code "laht"}
 * and {@code "LAHT"} would become two devices.
 *
 * <p>Null and blank are left to {@code @NotBlank}, as the package documents.
 */
@Documented
@Constraint(validatedBy = DeviceIdValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DeviceId {

    /** The protocol's identifier size, and therefore the field's maximum length. */
    int MAX_LENGTH = 4;

    String message() default "A device identifier is 1 to 4 letters or digits";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
