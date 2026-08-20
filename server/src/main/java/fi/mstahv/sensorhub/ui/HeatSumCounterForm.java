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
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.store.HeatSumCounter;
import org.vaadin.firitin.components.button.VButton;
import org.vaadin.firitin.form.BeanValidationForm;
import org.vaadin.firitin.layouts.HorizontalFloatLayout;

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
class HeatSumCounterForm extends Div {

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
        // A Div: the rows are blocks and stack on their own. This was a
        // VerticalLayout with its padding, spacing and even its default width all
        // overridden — three lines of configuration to arrive at a plain block.
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

        private final TextField comment = new CommentField();
        private final NumberField target = new TargetField();
        private final Checkbox alertBeforeTarget = new Checkbox("a day before");
        private final Checkbox alertAtTarget = new Checkbox("when reached");

        private final HeatSumCounter counter;
        private final Consumer<Long> onStop;

        ExistingCounter(HeatSumCounter counter, Consumer<ChangedCounter> onChange,
                        Consumer<Long> onStop) {
            super(ChangedCounter.class);
            asSection();
            this.counter = counter;
            this.onStop = onStop;

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
            return new Column(
                    new FieldRow(comment, target, new StopButton()),
                    new NotifyChoices(alertBeforeTarget, alertAtTarget),
                    new Started(counter.getStartedAt()));
        }

        private class StopButton extends VButton {
            StopButton() {
                super(VaadinIcon.TRASH);
                addThemeVariants(ButtonVariant.TERTIARY);
                setAriaLabel("Stop this counter");

                addClickListener(event -> onStop.accept(counter.getId()));
            }
        }
    }

    /**
     * Starting a new one. The start time is now: a counter is begun when the meat
     * goes up, and backdating it is the rare case — the target can be adjusted
     * instead, which comes to the same thing.
     */
    private static class StartCounter extends BeanValidationForm<NewCounter> {

        private final TextField comment = new CommentField();
        private final NumberField target = new TargetField();

        StartCounter(Consumer<NewCounter> onStart) {
            super(NewCounter.class);
            asSection();

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
            /*
               A Div rather than a layout: with its padding and spacing switched off,
               a VerticalLayout was doing nothing a plain block does not — and a Div
               has nothing to switch off.
            */
            return new Div(
                    new FieldRow(comment, target, getSaveButton()),
                    getClassLevelViolationsDisplay(),
                    new Hint("Degree-days: temperature multiplied by time. Forty is the usual "
                            + "guideline for hanging game, some prefer sixty. Time below freezing "
                            + "does not count."));
        }

        /*
           A plain tertiary button rather than the form's DefaultButton, for two
           reasons that are really one: this starts a row among the popover's
           content, and the popover's verdict is the settings form's Save above.
           Tertiary styling says the first; skipping DefaultButton's ENTER shortcut
           says the second — a form keeping the default look would say it with
           setSaveOnEnter(false).
        */
        @Override
        protected Button createSaveButton() {
            return new Button(getSaveCaption()){{
                addThemeVariants(ButtonVariant.TERTIARY);
                setVisible(false);
            }};
        }
    }

    /**
     * What is hanging. Flexible with a floor rather than full width: full width
     * would make the field the whole of a wrapping row, so everything after it
     * would always drop to the next line. This way the row stays one line while
     * there is room for all of it. The length limit comes from the record's
     * {@code @Size}, via the binder.
     */
    private static class CommentField extends TextField {
        CommentField() {
            setPlaceholder("What is hanging");
            getStyle().set("flex", "1 1 11em");
        }
    }

    /** Which of the counter's two moments this browser wants to hear about. */
    private static class NotifyChoices extends HorizontalFloatLayout {
        NotifyChoices(Checkbox before, Checkbox at) {
            add(new Caption("Notify:"), before, at);
        }
    }

    /** The target in degree-days, in both rows. */
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

    private static class Started extends SecondaryText {
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

    private static class Caption extends SecondaryText {
        Caption(String text) {
            super(text);
        }
    }

    static String formatDegreeDays(double degreeDays) {
        return String.format(Locale.ROOT, "%.1f", degreeDays);
    }
}
