package fi.mstahv.sensorhub.ui;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
class HeatSumPanel extends VerticalLayout {

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
        setPadding(false);
        setSpacing(false);
        setWidthFull();
        getStyle().setMarginTop("var(--vaadin-gap-s)");

        setVisible(!counters.isEmpty());
        counters.forEach(progress -> add(new CounterRow(progress)));
    }

    private static class CounterRow extends VerticalLayout {

        CounterRow(CounterProgress progress) {
            setPadding(false);
            setSpacing(false);
            setWidthFull();
            getStyle().setMarginBottom("var(--vaadin-gap-xs)");

            HeatSumCounter counter = progress.counter();
            HeatSum sum = progress.sum();

            add(new Label("%s · %s / %s °Cd".formatted(
                    counter.describe(), format(sum.degreeDays()), format(counter.getTarget()))));

            ProgressBar bar = new ProgressBar(0, counter.getTarget(),
                    Math.min(sum.degreeDays(), counter.getTarget()));
            bar.getStyle().setMarginTop("0.125rem");
            bar.getStyle().setMarginBottom("0.125rem");
            add(bar);

            add(new Forecast(sum, counter.getTarget()));
        }

        private static class Label extends Span {
            Label(String text) {
                super(text);
                getStyle().setFontSize("0.8125rem");
            }
        }

        /**
         * What is left, in the terms the reader thinks in: a date and time when it
         * will be done, not a number of degree-days.
         */
        private static class Forecast extends Span {
            Forecast(HeatSum sum, double target) {
                super(describe(sum, target));
                getStyle().setFontSize("0.75rem");
                getStyle().setColor("var(--vaadin-text-color-secondary)");
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
