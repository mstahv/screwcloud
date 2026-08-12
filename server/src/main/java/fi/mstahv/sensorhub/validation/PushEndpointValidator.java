package fi.mstahv.sensorhub.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces {@link PushEndpoint}: parseable, absolute, https, and with a host.
 */
public class PushEndpointValidator implements ConstraintValidator<PushEndpoint, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        return uri.isAbsolute()
                && "https".equals(lowerCase(uri.getScheme()))
                && uri.getHost() != null;
    }

    private static String lowerCase(String scheme) {
        return scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
    }
}
