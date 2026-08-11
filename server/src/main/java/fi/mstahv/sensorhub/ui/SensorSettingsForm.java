package fi.mstahv.sensorhub.ui;

import java.util.function.Consumer;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;

import fi.mstahv.sensorhub.store.AlertPreferences;
import fi.mstahv.sensorhub.store.SensorThresholds;

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
 */
class SensorSettingsForm extends VerticalLayout {

    private final TemperatureField alertLow;
    private final TemperatureField okLow;
    private final TemperatureField okHigh;
    private final TemperatureField alertHigh;
    private final AlertChoices alerts;

    /**
     * Everything the form collected.
     *
     * @param alerts null when the alert section was not shown, which is not the
     *        same as "subscribed to nothing" and must not overwrite stored choices
     */
    record Values(String name, SensorThresholds thresholds, AlertPreferences alerts) {
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

    /**
     * @param counters the degree-day counter management, built by the caller because
     *        it needs the store; null leaves the section out
     */
    SensorSettingsForm(String sensorId, String currentName, SensorThresholds currentThresholds,
                       AlertOptions alertOptions, Component counters, Consumer<Values> onSave) {
        setPadding(false);
        setSpacing(false);
        setWidth("20rem");

        TextField name = new TextField("Name");
        name.setPlaceholder(sensorId);
        name.setValue(currentName != null ? currentName : "");
        name.setHelperText("Empty = show the identifier " + sensorId);
        name.setWidthFull();
        name.setMaxLength(64);

        /*
           The visible caption belongs to the row, not to either field, so each
           field carries its own aria label. Without one the four inputs are
           distinguishable only by position — to a screen reader as much as to a
           test.
        */
        okLow = new TemperatureField("OK low", currentThresholds.okLow());
        okHigh = new TemperatureField("OK high", currentThresholds.okHigh());
        alertLow = new TemperatureField("Alert low", currentThresholds.alertLow());
        alertHigh = new TemperatureField("Alert high", currentThresholds.alertHigh());
        alerts = alertOptions.available() ? new AlertChoices(alertOptions) : null;

        Button save = new Button("Save", event -> onSave.accept(
                new Values(name.getValue(), readThresholds(),
                        alerts == null ? null : alerts.getPreferences())));
        save.addThemeVariants(ButtonVariant.PRIMARY);
        save.getStyle().setMarginTop("var(--vaadin-gap-s)");

        add(name,
                new SectionLabel("Temperature bands (°C)"),
                new LimitRow("OK between", okLow, okHigh),
                new LimitRow("Alert below / above", alertLow, alertHigh),
                new Hint("Leave all four empty for the default gauge. "
                        + "Warning is what falls between the OK and alert limits."));
        if (alerts != null) {
            add(new SectionLabel("Notify this browser when"), alerts);
        }
        if (counters != null) {
            add(new SectionLabel("Degree-day counters"), counters);
        }
        add(save);
    }

    private SensorThresholds readThresholds() {
        return new SensorThresholds(alertLow.getValue(), okLow.getValue(),
                okHigh.getValue(), alertHigh.getValue());
    }

    /**
     * A temperature limit input. Narrow on purpose: the values are a handful of
     * degrees, and a full width field would suggest more precision than a
     * half-degree step offers.
     */
    private static class TemperatureField extends NumberField {
        TemperatureField(String ariaLabel, Double value) {
            setAriaLabel(ariaLabel);
            setStep(0.5);
            setWidth("4em");
            // null clears the field, which is how "no band configured" is entered.
            setValue(value);
        }
    }

    /**
     * The three transitions that can be subscribed to, phrased as what happens
     * rather than as the name of a band.
     */
    private static class AlertChoices extends VerticalLayout {

        private final Checkbox onAlert = new Checkbox("it goes into an alert band");
        private final Checkbox onWarning = new Checkbox("it goes into a warning band");
        private final Checkbox onRecovery = new Checkbox("it comes back to OK");

        AlertChoices(AlertOptions options) {
            setPadding(false);
            setSpacing(false);
            setWidthFull();

            onAlert.setValue(options.preferences().onAlert());
            onWarning.setValue(options.preferences().onWarning());
            onRecovery.setValue(options.preferences().onRecovery());

            add(onAlert, onWarning, onRecovery);

            /*
               Two things can silently make these do nothing, and both are worth
               saying out loud here rather than leaving the reader to wonder why no
               notification ever arrives.
            */
            if (!options.pushSubscribed()) {
                add(new Hint("Notifications are switched off for this browser. "
                        + "Turn them on from the front page — these choices are kept "
                        + "in the meantime."));
            }
            add(new Hint("Alerts need the temperature bands above: without limits "
                    + "there is nothing to leave or return to."));
        }

        AlertPreferences getPreferences() {
            return new AlertPreferences(
                    onAlert.getValue(), onWarning.getValue(), onRecovery.getValue());
        }
    }

    private static class SectionLabel extends Span {
        SectionLabel(String text) {
            super(text);
            getStyle().setMarginTop("var(--vaadin-gap-m)");
            getStyle().setFontSize("0.875rem");
            getStyle().setColor("var(--vaadin-text-color-secondary)");
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
            caption.getStyle().setFontSize("0.8125rem");
            caption.getStyle().setMinWidth("9rem");

            add(caption, low, new RangeSeparator(), high);
        }
    }

    private static class RangeSeparator extends Span {
        RangeSeparator() {
            super("–");
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
}
