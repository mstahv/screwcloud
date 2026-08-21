package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.aura.Aura;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.vaadin.firitin.form.BeanValidationForm;

import fi.mstahv.sensorhub.store.DeviceSettingsStore;

/**
 * One device's own settings: the identifier as it is, the name it goes by
 * here, and the building it wears as an icon.
 *
 * <p>The identifier is four characters because it travels in a UDP packet and a
 * {@code config.h}; the name is for the person reading this UI, who is looking
 * for their cabin rather than for {@code LAHT}. The screen shows both, because
 * the identifier is what connects the friendly name back to the hardware — it
 * stays visible even while a name replaces it everywhere else. The icon is the
 * same thought one step further: a device sits in a building, and the building
 * is how its owner recognises it at a glance, before any word is read.
 *
 * <p>A view of its own rather than a popover, reached from the pencil in the
 * measurement view's header. Everything here is a fact about the device, not a
 * per-browser preference (the same reasoning as sensor names in
 * {@link fi.mstahv.sensorhub.store.SensorSettingsStore}), so everyone watching
 * the device sees what is chosen here.
 *
 * <p>Saving returns to the measurements: this screen is an errand, not a place.
 */
@Route("device-settings")
@StyleSheet(Aura.STYLESHEET)
// After Aura, because it sets the tokens Aura reads. See the file.
@StyleSheet("/styles/sunset-glass.css")
public class DeviceSettingsView extends NavigationView
        implements HasUrlParameter<String>, HasDynamicTitle {

    /**
     * What the form collects. The name's one rule — the length — matches the
     * column it ends up in, and the binder carries it onto the field. The icon
     * is never null because "no icon" is a choice of its own,
     * {@link DeviceIcon#NONE}: a radio group cannot be deselected, so the
     * absence has to be somewhere the reader can put it back.
     */
    record Settings(@Size(max = 64) String name, @NotNull DeviceIcon icon) {
    }

    private final DeviceSettingsStore settings;
    private final SecondaryText identifier = new SecondaryText();
    private final SettingsForm form = new SettingsForm();

    private String deviceId;

    public DeviceSettingsView(DeviceSettingsStore settings) {
        /*
           Up from here is the device's measurements, a parameterised route — the
           parameter reaches the arrow in setParameter, where the URL hands it
           over; until then the arrow points at the route without one.
        */
        super("Device settings", DashboardView.class, "Measurements");
        this.settings = settings;
        add(identifier, form);
    }

    @Override
    public void setParameter(BeforeEvent event, String deviceId) {
        this.deviceId = deviceId;
        setBackTarget(DashboardView.class, deviceId);
        identifier.setText("Device ID: " + deviceId);
        form.name.setPlaceholder(deviceId);
        form.name.setHelperText("Empty = show the identifier " + deviceId);
        /*
           Enabled from the start: what is on screen is what is stored, so it is
           already a valid thing to save — and clearing a field back to empty
           is itself a change worth saving.
        */
        form.setEntityWithEnabledSave(new Settings(
                settings.nameFor(deviceId),
                DeviceIcon.fromToken(settings.iconFor(deviceId))));
    }

    @Override
    public String getPageTitle() {
        return deviceId != null ? deviceId + " settings · ScrewCloud" : "ScrewCloud";
    }

    private class SettingsForm extends BeanValidationForm<Settings> {

        /* Named after the record's components, which is how FormBinder finds them. */
        private final TextField name = new TextField("Name");
        private final RadioButtonGroup<DeviceIcon> icon = new RadioButtonGroup<>("Icon");

        SettingsForm() {
            super(Settings.class);
            // One section of the page, not the page.
            asSection();

            icon.setItems(DeviceIcon.values());
            /*
               The drawing is the choice, so the drawing is what each radio
               shows — the caption alone would make the reader pick a word and
               hope. NONE has no drawing, and its caption is the whole option.
            */
            icon.setRenderer(new ComponentRenderer<>(DeviceSettingsView::choice));

            setSaveCaption("Save");
            setSavedHandler(this::save);
        }

        private void save(Settings values) {
            settings.rename(deviceId, values.name());
            settings.setIcon(deviceId, values.icon().token());
            Notification.show("Saved");
            // Back to the measurements: the errand is done.
            getUI().ifPresent(ui -> ui.navigate(DashboardView.class, deviceId));
        }

        @Override
        protected Component createContent() {
            /*
               The field's width is the length of a name, not of the screen: full
               width on a desktop made a 64-character rule look like an invitation
               to write a sentence.
            */
            name.setWidth("min(20rem, 100%)");
            return new Column(name, icon, getSaveButton());
        }
    }

    /** One option of the icon group: the drawing beside its word. */
    private static Component choice(DeviceIcon icon) {
        if (icon == DeviceIcon.NONE) {
            return new Span(icon.caption());
        }
        HorizontalLayout option = new HorizontalLayout(
                icon.image("1.75rem"), new Span(icon.caption()));
        option.setAlignItems(FlexComponent.Alignment.CENTER);
        option.setSpacing(false);
        option.getStyle().setGap("0.375rem");
        return option;
    }
}
