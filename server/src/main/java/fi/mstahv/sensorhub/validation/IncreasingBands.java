package fi.mstahv.sensorhub.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The four temperature limits are either all given or all empty, and they
 * increase.
 *
 * <p>A class level constraint, because it is about the values as a set: no single
 * field is wrong on its own when the OK band is upside down. This is the rule that
 * cannot be written as a property annotation, and the reason a form binder that
 * only knows per-field constraints was never enough here.
 *
 * <p>Goes on anything implementing {@link TemperatureBands}: the record that is
 * stored and the record the form collects.
 */
@Documented
@Constraint(validatedBy = IncreasingBandsValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IncreasingBands {

    /**
     * Only a fallback. The validator says which of the two ways the set is wrong,
     * since "the limits are not in order" is of no help to someone who has filled
     * in three of four fields.
     */
    String message() default "The temperature limits are not a usable set";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
