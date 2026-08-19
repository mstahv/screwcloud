package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.customfield.CustomField;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import fi.mstahv.sensorhub.store.AlertPreferences;
import org.vaadin.firitin.form.FormBinder;

/**
 * Which of a sensor's alerts this browser wants, edited as one value.
 *
 * <p>The three transitions are phrased as what happens rather than as the name of a
 * band. Like {@link TemperatureBandsField} this is a {@link CustomField} over the
 * record the store already takes, so the settings form binds an
 * {@link AlertPreferences} rather than three booleans it would have to assemble.
 *
 * @see TemperatureBandsField for why the value is updated manually and only for
 *      changes that came from the client
 */
class AlertChoicesField extends CustomField<AlertPreferences> {

    private final Checkbox onAlert = new Checkbox("it goes into an alert band");
    private final Checkbox onWarning = new Checkbox("it goes into a warning band");
    private final Checkbox onRecovery = new Checkbox("it comes back to OK");

    private final FormBinder<AlertPreferences> binder;

    /**
     * @param pushSubscribed whether this browser has notifications switched on at
     *        all; if not, the choices are stored but nothing will arrive, and that
     *        is worth saying here
     */
    AlertChoicesField(boolean pushSubscribed) {
        super(AlertPreferences.NONE, true);

        VerticalLayout layout = new VerticalLayout(onAlert, onWarning, onRecovery);
        layout.setPadding(false);
        layout.setSpacing(false);
        layout.setWidthFull();

        /*
           Two things can silently make these do nothing, and both are worth saying
           out loud here rather than leaving the reader to wonder why no notification
           ever arrives.
        */
        if (!pushSubscribed) {
            layout.add(new Hint("Notifications are switched off for this browser. "
                    + "Turn them on from the front page — these choices are kept "
                    + "in the meantime."));
        }
        layout.add(new Hint("Alerts need the temperature bands above: without limits "
                + "there is nothing to leave or return to."));
        add(layout);

        binder = new FormBinder<>(AlertPreferences.class, this);
        binder.addValueChangeListener(event -> {
            if (event.isFromClient()) {
                updateValue();
            }
        });
    }

    @Override
    protected AlertPreferences generateModelValue() {
        return binder.getValue();
    }

    @Override
    protected void setPresentationValue(AlertPreferences preferences) {
        binder.setValue(preferences == null ? AlertPreferences.NONE : preferences);
    }

}
