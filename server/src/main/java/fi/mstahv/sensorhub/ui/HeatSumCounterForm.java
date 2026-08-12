package fi.mstahv.sensorhub.ui;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.store.HeatSumCounter;
import org.vaadin.firitin.form.BeanValidationForm;
import org.vaadin.firitin.util.style.VaadinCssProps;

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
 *
 * <p>Each row is a bound form of its own. The constraints are the ones the store
 * and the entity already state — a target above zero, a comment that fits — so an
 * impossible value is refused where it is typed instead of coming back from the
 * store as a toast.
 */
class HeatSumCounterForm extends VerticalLayout {

    /** What the form hands back when a counter is started. */
    record NewCounter(@Size(max = HeatSumCounter.MAX_COMMENT_LENGTH) String comment,
                      @NotNull @Positive(message = "The target has to be more than zero degree-days")
                      Double target) {
    }

    /**
     * A running counter as its row edits it, identifier included.
     *
     * <p>The identifier has no field and needs none: the binder keeps the value it
     * was given for a component nothing edits. That is what lets one record be both
     * what the form binds and what it hands back — the alternative was two records
     * and a hand written copy from one to the other.
     */
    record ChangedCounter(long id,
                          @Size(max = HeatSumCounter.MAX_COMMENT_LENGTH) String comment,
                          @NotNull @Positive(message = "The target has to be more than zero degree-days")
                          Double target,
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
     *
     * <p>Which is why this form has no save button of its own and listens to the
     * binder instead — and why it saves only what validates. A row that saves on
     * every change is otherwise one keystroke away from storing a half-typed value,
     * since emptying the field is what happens on the way to typing a new number.
     * The {@code @NotNull} on the target makes the field required as well, so that
     * particular keystroke is refused before it gets here; the check stays because
     * the next constraint may not have a widget to enforce it.
     */
    private static class ExistingCounter extends BeanValidationForm<ChangedCounter> {

        private final TextField comment = new TextField();
        private final NumberField target = new TargetField();
        private final Checkbox alertBeforeTarget = new Checkbox("a day before");
        private final Checkbox alertAtTarget = new Checkbox("when reached");

        private final HeatSumCounter counter;
        private final Consumer<Long> onStop;

        ExistingCounter(HeatSumCounter counter, Consumer<ChangedCounter> onChange,
                        Consumer<Long> onStop) {
            super(ChangedCounter.class);
            // A row, not a page: the wrapping Div is size full by default.
            getContent().setWidthFull();
            getContent().setHeight(null);
            this.counter = counter;
            this.onStop = onStop;

            comment.setPlaceholder("What is hanging");
            comment.setMaxLength(HeatSumCounter.MAX_COMMENT_LENGTH);
            comment.setWidthFull();

            setEntity(new ChangedCounter(counter.getId(), counter.getComment(),
                    counter.getTarget(), counter.isAlertBeforeTarget(),
                    counter.isAlertAtTarget()));

            /*
               Added after setEntity, so it runs after the form's own listener has
               validated the change and marked the fields. isValid() therefore
               answers about the value that was just typed.
            */
            getBinder().addValueChangeListener(event -> {
                if (event.isFromClient() && isValid()) {
                    onChange.accept(getEntity());
                }
            });
        }

        @Override
        protected Component createContent() {
            Button stop = new Button(VaadinIcon.TRASH.create(),
                    event -> onStop.accept(counter.getId()));
            stop.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
            stop.setAriaLabel("Stop this counter");

            HorizontalLayout row = new HorizontalLayout(comment, target, stop);
            row.setAlignItems(Alignment.BASELINE);
            row.setWidthFull();
            row.setPadding(false);

            HorizontalLayout alerts = new HorizontalLayout(new Caption("Notify:"),
                    alertBeforeTarget, alertAtTarget);
            alerts.setAlignItems(Alignment.CENTER);
            alerts.setPadding(false);

            VerticalLayout layout = new VerticalLayout(row, alerts,
                    new Started(counter.getStartedAt()));
            layout.setPadding(false);
            layout.setWidthFull();
            return layout;
        }

        @Override
        protected List<Component> getFormComponents() {
            return List.of();
        }
    }

    /**
     * Starting a new one. The start time is now: a counter is begun when the meat
     * goes up, and backdating it is the rare case — the target can be adjusted
     * instead, which comes to the same thing.
     */
    private static class StartCounter extends BeanValidationForm<NewCounter> {

        private final TextField comment = new TextField();
        private final NumberField target = new TargetField();

        StartCounter(Consumer<NewCounter> onStart) {
            super(NewCounter.class);
            getContent().setWidthFull();
            getContent().setHeight(null);

            comment.setPlaceholder("What is hanging");
            comment.setMaxLength(HeatSumCounter.MAX_COMMENT_LENGTH);
            comment.setWidthFull();

            setSaveCaption("Start");
            setSavedHandler(started -> {
                onStart.accept(started);
                // Ready for the next one, rather than showing what was just started.
                setEntityWithEnabledSave(blank());
            });
            /*
               A counter with no comment is a perfectly good counter, so Start is
               available before anything is typed.
            */
            setEntityWithEnabledSave(blank());
        }

        private static NewCounter blank() {
            return new NewCounter(null, HeatSumCounter.DEFAULT_TARGET);
        }

        @Override
        protected Component createContent() {
            HorizontalLayout row = new HorizontalLayout(comment, target, getSaveButton());
            row.setAlignItems(Alignment.BASELINE);
            row.setWidthFull();
            row.setPadding(false);

            VerticalLayout layout = new VerticalLayout(row, getClassLevelViolationsDisplay(),
                    new Hint("Degree-days: temperature multiplied by time. Forty is the usual "
                            + "guideline for hanging game, some prefer sixty. Time below freezing "
                            + "does not count."));
            layout.setPadding(false);
            layout.setSpacing(false);
            layout.setWidthFull();
            return layout;
        }

        @Override
        protected List<Component> getFormComponents() {
            return List.of();
        }

        /*
           A plain button rather than the Viritin default one the form would make.
           That default hooks itself to ENTER, and this form shares a popover with
           the settings form's Save — which does the same, and is the one that should
           have it. Two of them would mean one keypress doing two things.
        */
        @Override
        protected Button createSaveButton() {
            Button start = new Button(getSaveCaption());
            start.addThemeVariants(ButtonVariant.TERTIARY);
            start.setVisible(false);
            return start;
        }
    }

    /** The target in degree-days, in both rows. */
    private static class TargetField extends NumberField {
        TargetField() {
            setAriaLabel("Target in degree-days");
            setStep(5);
            setMin(1);
            setWidth("5em");
            setSuffixComponent(new Span("°Cd"));
        }
    }

    /**
     * Secondary text: the colour says it is secondary, and the theme decides the
     * size. Three classes rather than one because they name what they are.
     */
    private static class Started extends Secondary {
        Started(Instant startedAt) {
            super("Started " + Ages.format(startedAt));
        }
    }

    private static class Caption extends Secondary {
        Caption(String text) {
            super(text);
        }
    }

    private static class Hint extends Secondary {
        Hint(String text) {
            super(text);
        }
    }

    private static class Secondary extends Span {
        Secondary(String text) {
            super(text);
            getStyle().setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var());
        }
    }

    static String formatDegreeDays(double degreeDays) {
        return String.format(Locale.ROOT, "%.1f", degreeDays);
    }
}
