package fi.mstahv.sensorhub.ui;

import java.util.function.Consumer;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.store.AlertPreferences;
import fi.mstahv.sensorhub.store.SensorThresholds;
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
class SensorSettingsForm extends RowForm<SensorSettingsForm.Values> {

    /**
     * Everything the form collects: the two domain values as they are, not taken
     * apart into the fields that edit them.
     *
     * <p>That is what the composite fields below are for. {@code @Valid} carries the
     * check into {@link SensorThresholds}, which already states that its four limits
     * increase — so the rule is written once, on the type it belongs to, and the
     * violation lands on the field that holds them.
     */
    record Values(@Size(max = 64) String name,
                  @Valid SensorThresholds thresholds,
                  AlertPreferences alerts) {
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
       The bound fields, one per component of the record. They are declared here, in
       the form class itself: FormBinder finds the editors by reflecting over this
       class's own fields, and their names are the record's component names — that is
       the binding.
    */
    private final TextField name = new TextField("Name");
    private final TemperatureBandsField thresholds = new TemperatureBandsField();
    private final AlertChoicesField alerts;

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
        this.alerts = new AlertChoicesField(alertOptions.pushSubscribed());
        this.counters = counters;

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
        setEntityWithEnabledSave(
                new Values(currentName, currentThresholds, alertOptions.preferences()));
    }

    @Override
    protected Component createContent() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        /*
           23rem is what the two limit rows need to line up. On a phone that is wider
           than the screen, and a popover sizes itself to its content — so the form
           kept its width, the overlay could not, and the right-hand end of every row
           was behind a horizontal scrollbar. Whichever is smaller therefore wins; the
           subtraction is the popover's own padding and the margin it keeps from the
           edge of the screen.
        */
        layout.setWidth("min(23rem, calc(100vw - 5rem))");

        layout.add(name, new SectionLabel("Temperature bands (°C)"), thresholds);
        /*
           Kept for anything that belongs to the form as a whole rather than to one
           of its fields. The limits are no longer such a case — their rule travels
           with the value they describe, so it is reported on the field itself.
        */
        layout.add(getClassLevelViolationsDisplay());

        if (alertOptions.available()) {
            layout.add(new SectionLabel("Notify this browser when"), alerts);
        }
        if (counters != null) {
            layout.add(new SectionLabel("Degree-day counters"), counters);
        }
        layout.add(getSaveButton());
        return layout;
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
            /*
               A heading needs more space above it than below, or it reads as
               belonging to the hint it follows rather than to the fields it heads.
               The layout's own gap is the same on both sides, which is what made
               three sections read as one column of text.
            */
            getStyle().setMarginTop(VaadinCssProps.GAP_S.var());
        }
    }

}
