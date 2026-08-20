package fi.mstahv.sensorhub.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;

import fi.mstahv.sensorhub.alerts.HeatSum;
import fi.mstahv.sensorhub.alerts.Elapsed;
import fi.mstahv.sensorhub.store.HeatSumCounter;

/**
 * The degree-day counters running on one sensor, on its card.
 *
 * <p>Visible rather than behind a section, because a counter that has to be opened
 * to be seen is a counter nobody checks. Hidden entirely when there are none, so a
 * sensor that is just measuring a room stays as plain as it was.
 *
 * <p>Each counter shows what it is, how far it has come, and when it will be done.
 * The forecast is the interesting part — the sum alone does not answer "should I be
 * there on Saturday".
 */
class HeatSumPanel extends Column {

    /**
     * How the progress and the forecast are worked out for one counter. Kept as a
     * record so the card can be built from data rather than from a store.
     *
     * @param counter what is being counted
     * @param sum where it has got to
     */
    record CounterProgress(HeatSumCounter counter, HeatSum sum) {
    }

    HeatSumPanel(List<CounterProgress> counters) {
        /*
           Room against the neighbours, without indenting the content: the counters
           sit in a card between reading lines that stack tight, and a running
           counter is a different thing from a reading — the margin says so. Margin
           rather than padding, because the space belongs outside this component;
           and here rather than on the card, because only the card with counters
           needs it. Gone with the panel when there are none: setVisible is
           display:none, and a margin needs a box to hang on.
        */
        getStyle().set("margin-block", "var(--vaadin-gap-s)");

        setVisible(!counters.isEmpty());
        counters.forEach(progress -> add(new CounterRow(progress)));
    }

    private static class CounterRow extends Div {

        CounterRow(CounterProgress progress) {
            /*
               A Div has no width of its own, and the Column above aligns its
               children flex-start — so without this the row shrinks to its text
               and takes the progress bar with it. The bar's length against the
               card is the reading, so the row spans the card.
            */
            setWidthFull();

            HeatSumCounter counter = progress.counter();
            HeatSum sum = progress.sum();

            add(new Label("%s · %s / %s °Cd".formatted(
                    counter.describe(), format(sum.degreeDays()), format(counter.getTarget()))));

            add(new ProgressBar(0, counter.getTarget(),
                    Math.min(sum.degreeDays(), counter.getTarget())));

            add(new Forecast(sum, counter.getTarget()));
        }

        private static class Label extends Span {
            Label(String text) {
                super(text);
            }
        }

        /**
         * What is left, in the terms the reader thinks in: a date and time when it
         * will be done, not a number of degree-days.
         */
        private static class Forecast extends SecondaryText {
            Forecast(HeatSum sum, double target) {
                super(describe(sum, target));
            }

            private static String describe(HeatSum sum, double target) {
                if (sum.reached(target)) {
                    return "Ready";
                }
                Optional<Duration> remaining = sum.remaining(target);
                if (remaining.isPresent()) {
                    String estimate = "About %s left, done %s".formatted(
                            Elapsed.approximate(remaining.get()), completion(remaining.get()));
                    /*
                       A counter younger than the device's send interval has no
                       readings of its own yet, so this came from the current
                       temperature alone. Saying so is the difference between a
                       number the reader can lean on and one they cannot.
                    */
                    return sum.provisional()
                            ? estimate + " — from the current temperature, sharpens as it runs"
                            : estimate;
                }
                /*
                   Two different reasons for having no forecast, and saying the wrong
                   one is how this read "below freezing" in a warm room: an empty rate
                   means no readings yet, a rate of zero means the shed is frozen.
                */
                if (sum.recentRate().isEmpty()) {
                    return "Waiting for the first reading";
                }
                return "Not accumulating — below freezing";
            }

            private static String completion(Duration remaining) {
                Instant done = Instant.now().plus(remaining);
                return TimeText.dayAndTime(done);
            }
        }
    }

    private static String format(double degreeDays) {
        return String.format(Locale.ROOT, "%.1f", degreeDays);
    }
}
