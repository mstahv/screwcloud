package fi.mstahv.sensorhub.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;

import org.vaadin.firitin.layouts.Column;
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
 *
 * <p>Reaching the target does not stop a counter. The meat is still hanging until
 * somebody takes it down, and the sum it has reached by then is the number they
 * want written down — so the sum keeps climbing past the target, and the card
 * says so in a way that cannot be missed: a badge, a bar turned green, and the
 * overshoot spelled out. Stopping is the reader's act, from the cog.
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
            double target = counter.getTarget();

            add(new Label("%s · %s / %s °Cd".formatted(
                    counter.describe(), format(sum.degreeDays()), format(target))));

            /*
               The bar is the reading at a glance, and it stops at the target: a bar
               that kept filling past its end would need a second scale nobody
               has. Past the target it turns green, and the badge beside it says by
               how much — the number that keeps moving lives in the label above.
            */
            ProgressBar bar = new ProgressBar(0, target, Math.min(sum.degreeDays(), target));
            if (sum.reached(target)) {
                bar.addThemeVariants(ProgressBarVariant.LUMO_SUCCESS);
                add(new TargetReached(sum.degreeDays() - target));
            }
            add(bar);

            add(new Forecast(sum, target));
        }

        /**
         * The one thing on the card that must not be missed: the target is behind
         * this counter, and everything it adds from here on is extra hanging time.
         * A badge rather than a line of text, because a line of text is what the
         * forecast under the bar already is, and this is a different kind of fact.
         */
        private static class TargetReached extends Badge {
            TargetReached(double over) {
                setText(over < 0.05
                        ? "Target reached"
                        : "Target reached · %s °Cd over".formatted(format(over)));
                addThemeVariants(BadgeVariant.SUCCESS);
                getStyle().setMarginBottom("var(--vaadin-gap-xs)");
            }
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
                    /*
                       Still counting, and said so: a reader who sees "Ready" and a
                       sum that moved since yesterday would wonder which to believe.
                       Both are true, and the sentence has room for both.
                    */
                    return "Past the target and still counting — stop the counter from "
                            + "the cog when it comes down";
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
