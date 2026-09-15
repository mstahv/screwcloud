package fi.mstahv.sensorhub.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Empty, or within WPA2's range. See {@link WifiPassphrase} for why both ends of
 * that range are the standard's rather than this application's.
 */
public class WifiPassphraseValidator implements ConstraintValidator<WifiPassphrase, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return value.length() >= WifiPassphrase.MIN_LENGTH
                && value.length() <= WifiPassphrase.MAX_LENGTH;
    }
}
