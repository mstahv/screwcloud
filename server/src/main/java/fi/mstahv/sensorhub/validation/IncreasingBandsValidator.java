package fi.mstahv.sensorhub.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces {@link IncreasingBands}, with a message that names the actual problem.
 */
public class IncreasingBandsValidator
        implements ConstraintValidator<IncreasingBands, TemperatureBands> {

    static final String INCOMPLETE = "Give all four temperature limits, or leave them all empty";

    static final String OUT_OF_ORDER =
            "The limits must increase: alert low < OK low < OK high < alert high";

    @Override
    public boolean isValid(TemperatureBands bands, ConstraintValidatorContext context) {
        if (bands == null) {
            return true;
        }

        Double[] limits = {bands.alertLow(), bands.okLow(), bands.okHigh(), bands.alertHigh()};
        int given = 0;
        for (Double limit : limits) {
            if (limit != null) {
                given++;
            }
        }

        // No bands at all is a valid choice: the gauge keeps its stock range.
        if (given == 0) {
            return true;
        }
        if (given < 4) {
            return fail(context, INCOMPLETE);
        }
        /*
           Strictly increasing rather than merely non-decreasing: an empty band
           would render as a zero-width arc, which looks like a rendering bug
           rather than a configuration choice.
        */
        if (!(limits[0] < limits[1] && limits[1] < limits[2] && limits[2] < limits[3])) {
            return fail(context, OUT_OF_ORDER);
        }
        return true;
    }

    /*
       Replacing the default message rather than adding to it, so the reader gets
       the one sentence that applies instead of that one plus the annotation's.
    */
    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
