package fi.mstahv.sensorhub.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A sensor identifier, as the device names its own sensors: {@code DHT},
 * {@code CPU}, {@code R1}, {@code RBF}.
 *
 * <p>Deliberately looser than {@link DeviceId}. A device identifier is chosen once
 * in config.h and normalised here; a sensor identifier is whatever a firmware puts
 * in the packet, including labels the owner of a device typed for their own tags.
 * This is not a rule about what a good identifier looks like — it is the fence that
 * keeps control characters and whitespace out of a column that is displayed as a
 * card title, when the packet they arrived in was unauthenticated and the decoder
 * only trims padding.
 *
 * <p>Composed rather than validated: printable, no spaces, and no longer than the
 * column. {@link ReportAsSingleViolation} keeps that one sentence rather than
 * reporting the size and the pattern separately.
 */
@Documented
@Constraint(validatedBy = {})
@Size(max = 8)
@Pattern(regexp = "[\\x21-\\x7E]+")
@ReportAsSingleViolation
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SensorId {

    String message() default
            "A sensor identifier is at most 8 printable characters and no spaces";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
