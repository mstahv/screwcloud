package fi.mstahv.sensorhub.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A web push endpoint: the absolute {@code https} URL of the browser vendor's push
 * service.
 *
 * <p>The value comes from the browser and the server later makes a request to it,
 * which is the whole reason for checking it. Storing whatever arrives would leave
 * this application posting to an address chosen by whoever could reach the session
 * — and {@code http} would send the notification in the clear.
 *
 * <p>Checked by parsing rather than by pattern: a URL is not a shape, and a regular
 * expression that looks like it understands one is the kind of thing that passes
 * review and fails in production.
 */
@Documented
@Constraint(validatedBy = PushEndpointValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PushEndpoint {

    String message() default "A push endpoint has to be an absolute https URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
