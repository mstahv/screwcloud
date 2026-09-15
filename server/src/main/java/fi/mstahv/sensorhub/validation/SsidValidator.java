package fi.mstahv.sensorhub.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Counts the SSID the way the radio will: in UTF-8 bytes.
 *
 * <p>Nothing is stripped or normalised here, unlike {@link DeviceIdValidator}. A
 * leading space in a network name is a leading space in the network name, and a
 * reader who types one meant it — or has a neighbour who did.
 */
public class SsidValidator implements ConstraintValidator<Ssid, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= Ssid.MAX_BYTES;
    }
}
