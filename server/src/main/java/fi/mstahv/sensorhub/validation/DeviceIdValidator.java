package fi.mstahv.sensorhub.validation;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Checks a device identifier the way the store will read it: stripped of
 * surrounding whitespace, and without caring about case.
 */
public class DeviceIdValidator implements ConstraintValidator<DeviceId, String> {

    private static final Pattern ALLOWED =
            Pattern.compile("[A-Za-z0-9]{1," + DeviceId.MAX_LENGTH + "}");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String normalised = value.strip();
        /*
           An empty identifier is a missing one, which is @NotBlank's question
           rather than this one's. Answering it here as well would show the reader
           two complaints about one empty field.
        */
        return normalised.isEmpty() || ALLOWED.matcher(normalised).matches();
    }
}
