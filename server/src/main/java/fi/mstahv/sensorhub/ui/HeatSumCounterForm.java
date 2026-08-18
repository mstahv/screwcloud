package fi.mstahv.sensorhub.ui;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.flowingcode.vaadin.addons.relativetime.RelativeTime;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayoutVariant;
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
     * <p>Which is why this form has no save button of its own, and says so with an
     * eager saved handler: it saves after every change that leaves the row valid,
     * and shows the ones that do not. A row that saved on every change instead
     * would be one keystroke away from storing a half-typed value, since emptying
     * the field is what happens on the way to typing a new number.
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

            // The length limit comes from the record's @Size, via the binder.
            comment.setPlaceholder("What is hanging");
            /*
               Flexible with a floor rather than the full width. Full width makes the
               field the whole row, so on a wrapping row everything else would always
               be pushed to the next line; this way the row stays one line while
               there is room for all of it and breaks only when there is not.
            */
            comment.setWidth(null);
            comment.getStyle().set("flex", "1 1 11em");

            /*
               Saved as the reader types, and only when what they typed leaves the
               row usable: an emptied target or an overlong comment is shown on the
               field, and the counter keeps what it had.
            */
            setEagerSavedHandler(onChange::accept);
            setEntity(new ChangedCounter(counter.getId(), counter.getComment(),
                    counter.getTarget(), counter.isAlertBeforeTarget(),
                    counter.isAlertAtTarget()));
        }

        @Override
        protected Component createContent() {
            Button stop = new Button(VaadinIcon.TRASH.create(),
                    event -> onStop.accept(counter.getId()));
            stop.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
            stop.setAriaLabel("Stop this counter");

            HorizontalLayout row = new HorizontalLayout(comment, targetAnd(target, stop));
            row.setAlignItems(Alignment.BASELINE);
            row.setWidthFull();
            row.setPadding(false);
            row.addThemeVariants(HorizontalLayoutVariant.WRAP);

            HorizontalLayout alerts = new HorizontalLayout(new Caption("Notify:"),
                    alertBeforeTarget, alertAtTarget);
            alerts.setAlignItems(Alignment.CENTER);
            alerts.setPadding(false);
            alerts.addThemeVariants(HorizontalLayoutVariant.WRAP);

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

            // The length limit comes from the record's @Size, via the binder.
            comment.setPlaceholder("What is hanging");
            /*
               Flexible with a floor rather than the full width. Full width makes the
               field the whole row, so on a wrapping row everything else would always
               be pushed to the next line; this way the row stays one line while
               there is room for all of it and breaks only when there is not.
            */
            comment.setWidth(null);
            comment.getStyle().set("flex", "1 1 11em");

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
            HorizontalLayout row = new HorizontalLayout(comment, targetAnd(target, getSaveButton()));
            row.setAlignItems(Alignment.BASELINE);
            row.setWidthFull();
            row.setPadding(false);
            /*
               Wraps rather than overflows. This form lives in a popover, and a
               popover on a phone is narrower than a text field, a number field and a
               button standing side by side — which showed as a scrollbar under the
               row and the button off the right-hand edge.
            */
            row.addThemeVariants(HorizontalLayoutVariant.WRAP);

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
    /**
     * The target and the button that acts on it, kept together.
     *
     * <p>They wrap as one thing, so a row with no room for all three parts breaks
     * after the comment — "what is hanging" on its own line, the number and the
     * button on the next — rather than leaving the button stranded on a line by
     * itself under two fields.
     */
    private static HorizontalLayout targetAnd(NumberField target, Component action) {
        HorizontalLayout group = new HorizontalLayout(target, action);
        group.setAlignItems(Alignment.BASELINE);
        group.setPadding(false);
        return group;
    }

    private static class TargetField extends NumberField {
        TargetField() {
            setAriaLabel("Target in degree-days");
            setStep(5);
            /*
               Kept by hand: the constraint is @Positive, which the binder does not
               pass on to a field because a field's minimum is inclusive and
               "greater than zero" is not. A whole degree-day is the smallest target
               worth typing anyway.
            */
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
            super("Started ");
            /*
               A counter runs for weeks, and this form is opened and left open while
               somebody sets the next one up. The time therefore keeps itself current
               rather than being written once when the popover was built.
            */
            add(new RelativeTime(startedAt));
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
