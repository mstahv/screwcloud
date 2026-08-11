package fi.mstahv.sensorhub.ui;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;

import fi.mstahv.sensorhub.store.HeatSumCounter;

/**
 * Starting, adjusting and stopping the degree-day counters on one sensor.
 *
 * <p>Lives in the sensor's settings popover, next to the name and the temperature
 * bands, because it is the same kind of thing: what this thermometer is being used
 * for. The counters themselves are shown on the card, where they can be read
 * without opening anything.
 *
 * <p>The default target is forty degree-days, the general guideline for hanging
 * game. Both alerts default to on, since a counter nobody is told about is a
 * calendar reminder with extra steps.
 */
class HeatSumCounterForm extends VerticalLayout {

    /** What the form hands back when a counter is started. */
    record NewCounter(String comment, double target, Instant startedAt) {
    }

    /** What the form hands back when an existing counter is changed. */
    record ChangedCounter(long id, String comment, double target,
                          boolean alertBeforeTarget, boolean alertAtTarget) {
    }

    HeatSumCounterForm(List<HeatSumCounter> existing,
                       Consumer<NewCounter> onStart,
                       Consumer<ChangedCounter> onChange,
                       Consumer<Long> onStop) {
        setPadding(false);
        setSpacing(false);
        setWidthFull();

        existing.forEach(counter -> add(new ExistingCounter(counter, onChange, onStop)));
        add(new StartCounter(onStart));
    }

    /**
     * One running counter: its comment, its target and whether it should say
     * anything. Saved on change rather than behind another button — the outer form
     * already has a Save, and two save buttons in one popover is a puzzle.
     */
    private static class ExistingCounter extends VerticalLayout {

        ExistingCounter(HeatSumCounter counter, Consumer<ChangedCounter> onChange,
                        Consumer<Long> onStop) {
            setPadding(false);
            setSpacing(false);
            setWidthFull();
            getStyle().setMarginBottom("var(--vaadin-gap-xs)");

            TextField comment = new TextField();
            comment.setValue(counter.getComment() == null ? "" : counter.getComment());
            comment.setPlaceholder("What is hanging");
            comment.setMaxLength(HeatSumCounter.MAX_COMMENT_LENGTH);
            comment.setWidthFull();

            NumberField target = new NumberField();
            target.setAriaLabel("Target in degree-days");
            target.setValue(counter.getTarget());
            target.setStep(5);
            target.setMin(1);
            target.setWidth("5em");
            target.setSuffixComponent(new Span("°Cd"));

            Button stop = new Button(VaadinIcon.TRASH.create(), event -> onStop.accept(counter.getId()));
            stop.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
            stop.setAriaLabel("Stop this counter");

            HorizontalLayout row = new HorizontalLayout(comment, target, stop);
            row.setAlignItems(Alignment.BASELINE);
            row.setWidthFull();
            row.setPadding(false);

            Checkbox before = new Checkbox("a day before");
            before.setValue(counter.isAlertBeforeTarget());
            Checkbox atTarget = new Checkbox("when reached");
            atTarget.setValue(counter.isAlertAtTarget());

            HorizontalLayout alerts = new HorizontalLayout(new Caption("Notify:"), before, atTarget);
            alerts.setAlignItems(Alignment.CENTER);
            alerts.setPadding(false);

            add(row, alerts, new Started(counter.getStartedAt()));

            Runnable save = () -> onChange.accept(new ChangedCounter(
                    counter.getId(), comment.getValue(),
                    target.getValue() == null ? counter.getTarget() : target.getValue(),
                    before.getValue(), atTarget.getValue()));
            comment.addValueChangeListener(event -> save.run());
            target.addValueChangeListener(event -> save.run());
            before.addValueChangeListener(event -> save.run());
            atTarget.addValueChangeListener(event -> save.run());
        }
    }

    /**
     * Starting a new one. The start time is now: a counter is begun when the meat
     * goes up, and backdating it is the rare case — the target can be adjusted
     * instead, which comes to the same thing.
     */
    private static class StartCounter extends VerticalLayout {

        StartCounter(Consumer<NewCounter> onStart) {
            setPadding(false);
            setSpacing(false);
            setWidthFull();

            TextField comment = new TextField();
            comment.setPlaceholder("What is hanging");
            comment.setMaxLength(HeatSumCounter.MAX_COMMENT_LENGTH);
            comment.setWidthFull();

            NumberField target = new NumberField();
            target.setAriaLabel("Target in degree-days");
            target.setValue(HeatSumCounter.DEFAULT_TARGET);
            target.setStep(5);
            target.setMin(1);
            target.setWidth("5em");
            target.setSuffixComponent(new Span("°Cd"));

            Button start = new Button("Start", event -> {
                onStart.accept(new NewCounter(comment.getValue(),
                        target.getValue() == null ? HeatSumCounter.DEFAULT_TARGET : target.getValue(),
                        Instant.now()));
                comment.clear();
                target.setValue(HeatSumCounter.DEFAULT_TARGET);
            });
            start.addThemeVariants(ButtonVariant.TERTIARY);

            HorizontalLayout row = new HorizontalLayout(comment, target, start);
            row.setAlignItems(Alignment.BASELINE);
            row.setWidthFull();
            row.setPadding(false);

            add(row, new Hint("Degree-days: temperature multiplied by time. Forty is the usual "
                    + "guideline for hanging game, some prefer sixty. Time below freezing does not "
                    + "count."));
        }
    }

    private static class Started extends Span {
        Started(Instant startedAt) {
            super("Started " + Ages.format(startedAt));
            getStyle().setFontSize("0.75rem");
            getStyle().setColor("var(--vaadin-text-color-secondary)");
        }
    }

    private static class Caption extends Span {
        Caption(String text) {
            super(text);
            getStyle().setFontSize("0.75rem");
            getStyle().setColor("var(--vaadin-text-color-secondary)");
        }
    }

    private static class Hint extends Span {
        Hint(String text) {
            super(text);
            getStyle().setFontSize("0.75rem");
            getStyle().setColor("var(--vaadin-text-color-secondary)");
            getStyle().setMarginTop("var(--vaadin-gap-xs)");
        }
    }

    static String formatDegreeDays(double degreeDays) {
        return String.format(Locale.ROOT, "%.1f", degreeDays);
    }
}
