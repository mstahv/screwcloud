package fi.mstahv.sensorhub.ui;

import java.util.List;
import java.util.function.Consumer;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;

import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.store.AlertPreferences;
import fi.mstahv.sensorhub.store.SensorThresholds;
import fi.mstahv.sensorhub.validation.IncreasingBands;
import fi.mstahv.sensorhub.validation.TemperatureBands;
import org.vaadin.firitin.form.BeanValidationForm;
import org.vaadin.firitin.util.style.VaadinCssProps;

/**
 * Settings for one sensor: the display name, the gauge's temperature bands, and
 * which of its alerts this browser wants to be notified about.
 *
 * <p>Opens in a popover from the card's settings icon and is rebuilt on every
 * open, so the fields always show the stored values and no state has to be
 * synchronised separately.
 *
 * <p>The four limits are entered as two ranges, which reads more naturally than
 * four independent thresholds: the OK band, and the points beyond which it is an
 * alert. Warning is what falls between the two.
 *
 * <p>The degree-day counters are managed here as well. They are not settings in the
 * same sense — a counter is a thing that is running — but they answer the same
 * question as the rest of this form: what is this thermometer being used for.
 *
 * <p>Bound with Viritin's {@link BeanValidationForm}, which is what ties the fields
 * to {@link Values} and its constraints: the fields are matched to the record's
 * components <b>by name</b>, every change is validated, and Save stays disabled
 * while anything is wrong. That is why a set of limits in the wrong order no longer
 * reaches the store and comes back as a toast — the reader is told where they are
 * standing, before they press anything.
 */
class SensorSettingsForm extends BeanValidationForm<SensorSettingsForm.Values> {

    /**
     * Everything the form collects, and the rules about it.
     *
     * <p>Flat rather than nested, because the binder matches fields to properties
     * by name and a nested record would have no field of its own to match. The two
     * domain values are assembled on the way out instead.
     *
     * <p>{@link IncreasingBands} is the same class level constraint that
     * {@link SensorThresholds} carries, reached through the shared
     * {@link TemperatureBands} interface — one rule, checked here while it is being
     * typed and again in the store before it is written.
     */
    @IncreasingBands
    record Values(@Size(max = 64) String name,
                  Double alertLow, Double okLow, Double okHigh, Double alertHigh,
                  boolean onAlert, boolean onWarning, boolean onRecovery)
            implements TemperatureBands {

        SensorThresholds toThresholds() {
            return new SensorThresholds(alertLow, okLow, okHigh, alertHigh);
        }

        AlertPreferences toAlerts() {
            return new AlertPreferences(onAlert, onWarning, onRecovery);
        }
    }

    /**
     * The state of alerts for this sensor and browser.
     *
     * @param preferences what is currently subscribed to
     * @param pushSubscribed whether this browser has notifications switched on at
     *        all; if not, the choices are stored but nothing will arrive
     * @param available false when the server has no VAPID keys or the browser
     *        token is not known yet, in which case the section is left out
     */
    record AlertOptions(AlertPreferences preferences, boolean pushSubscribed, boolean available) {

        AlertOptions(AlertPreferences preferences, boolean pushSubscribed) {
            this(preferences, pushSubscribed, true);
        }

        static AlertOptions unavailable() {
            return new AlertOptions(AlertPreferences.NONE, false, false);
        }
    }

    /*
       The bound fields. They are declared here, in the form class itself, rather
       than inside the little layout classes below: FormBinder finds the editors by
       reflecting over this class's own fields, and a field tucked into a nested
       layout would simply not be bound. Their names are the record's component
       names — that is the binding.
    */
    private final TextField name = new TextField("Name");
    private final NumberField okLow = new TemperatureField("OK low");
    private final NumberField okHigh = new TemperatureField("OK high");
    private final NumberField alertLow = new TemperatureField("Alert low");
    private final NumberField alertHigh = new TemperatureField("Alert high");
    private final Checkbox onAlert = new Checkbox("it goes into an alert band");
    private final Checkbox onWarning = new Checkbox("it goes into a warning band");
    private final Checkbox onRecovery = new Checkbox("it comes back to OK");

    private final AlertOptions alertOptions;
    private final Component counters;

    /**
     * @param counters the degree-day counter management, built by the caller because
     *        it needs the store; null leaves the section out
     */
    SensorSettingsForm(String sensorId, String currentName, SensorThresholds currentThresholds,
                       AlertOptions alertOptions, Component counters, Consumer<Values> onSave) {
        super(Values.class);
        this.alertOptions = alertOptions;
        this.counters = counters;
        /*
           The form is a Composite over a Div, and that Div is size full by
           default. In a popover, which sizes itself to its content, a full height
           child is a child with no height at all.
        */
        getContent().setSizeUndefined();

        name.setPlaceholder(sensorId);
        name.setHelperText("Empty = show the identifier " + sensorId);
        name.setWidthFull();
        // The length limit comes from Values.name's @Size, via the binder.

        setSaveCaption("Save");
        setSavedHandler(onSave::accept);

        /*
           Enabled from the start rather than only after a change: what is on screen
           is what was stored, so it is already a valid thing to save, and a Save
           button that ignores the first click is its own kind of puzzle. It still
           greys out the moment the values stop making sense.
        */
        setEntityWithEnabledSave(new Values(currentName,
                currentThresholds.alertLow(), currentThresholds.okLow(),
                currentThresholds.okHigh(), currentThresholds.alertHigh(),
                alertOptions.preferences().onAlert(),
                alertOptions.preferences().onWarning(),
                alertOptions.preferences().onRecovery()));
    }

    @Override
    protected Component createContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setWidth("23rem");

        layout.add(name,
                new SectionLabel("Temperature bands (°C)"),
                new LimitRow("OK between", okLow, okHigh),
                new LimitRow("Alert below / above", alertLow, alertHigh),
                new Hint("Leave all four empty for the default gauge. "
                        + "Warning is what falls between the OK and alert limits."));
        /*
           The one violation that belongs to no single field — the four limits as a
           set — is shown here, right under them, rather than wherever the form
           happens to end.
        */
        layout.add(getClassLevelViolationsDisplay());

        if (alertOptions.available()) {
            layout.add(new SectionLabel("Notify this browser when"), alertChoices());
        }
        if (counters != null) {
            layout.add(new SectionLabel("Degree-day counters"), counters);
        }
        layout.add(getSaveButton());
        return layout;
    }

    /**
     * Unused: {@link #createContent()} is overridden, since these fields do not go
     * into a form layout one after another — two of them share a row.
     */
    @Override
    protected List<Component> getFormComponents() {
        return List.of();
    }

    /**
     * The three transitions that can be subscribed to, phrased as what happens
     * rather than as the name of a band.
     */
    private Component alertChoices() {
        VerticalLayout choices = new VerticalLayout(onAlert, onWarning, onRecovery);
        choices.setPadding(false);
        choices.setSpacing(false);
        choices.setWidthFull();

        /*
           Two things can silently make these do nothing, and both are worth saying
           out loud here rather than leaving the reader to wonder why no notification
           ever arrives.
        */
        if (!alertOptions.pushSubscribed()) {
            choices.add(new Hint("Notifications are switched off for this browser. "
                    + "Turn them on from the front page — these choices are kept "
                    + "in the meantime."));
        }
        choices.add(new Hint("Alerts need the temperature bands above: without limits "
                + "there is nothing to leave or return to."));
        return choices;
    }

    /**
     * A temperature limit input. Narrow on purpose: the values are a handful of
     * degrees, and a full width field would suggest more precision than a
     * half-degree step offers.
     *
     * <p>The visible caption belongs to the row, not to either field, so each field
     * carries its own aria label. Without one the four inputs are distinguishable
     * only by position — to a screen reader as much as to a test.
     */
    private static class TemperatureField extends NumberField {
        TemperatureField(String ariaLabel) {
            setAriaLabel(ariaLabel);
            setStep(0.5);
            setWidth("4em");
        }
    }

    /**
     * The form's own structure. Bold and in the normal text colour, because in the
     * secondary colour at hint size these three read as more hints rather than as the
     * headings that separate the sections.
     */
    private static class SectionLabel extends Span {
        SectionLabel(String text) {
            super(text);
            getStyle().setFontWeight(com.vaadin.flow.dom.Style.FontWeight.BOLD);
        }
    }

    /**
     * A caption and a pair of limits, separated by a dash so the two fields read
     * as one range rather than two unrelated numbers.
     */
    private static class LimitRow extends HorizontalLayout {
        LimitRow(String label, NumberField low, NumberField high) {
            setPadding(false);
            setSpacing(true);
            setAlignItems(Alignment.BASELINE);
            setWidthFull();

            Span caption = new Span(label);
            // Kept: the two rows' fields line up only if the captions share a width.
            caption.getStyle().setMinWidth("9rem");

            add(caption, low, new RangeSeparator(), high);
        }
    }

    private static class RangeSeparator extends Span {
        RangeSeparator() {
            super("–");
            getStyle().setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var());
        }
    }

    private static class Hint extends Span {
        Hint(String text) {
            super(text);
            getStyle().setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var());
        }
    }
}
