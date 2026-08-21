package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fi.mstahv.sensorhub.store.DeviceSettingsStore;

/**
 * A device's settings, driven the way a reader does it: the pencil on the
 * measurement view, the settings screen, and the choices showing up where the
 * identifier used to stand alone.
 */
@UiTest
class DeviceSettingsViewTest {

    @Autowired
    private DeviceSettingsStore settings;

    /* The identifier is what connects the friendly name back to the hardware. */
    @Test
    void theScreenShowsTheIdentifier(@Autowired BrowserlessUIContext ui) {
        ui.navigate(DeviceSettingsView.class, "NAME");

        assertTrue(ui.findSpan().withTextContaining("Device ID: NAME").exists());
    }

    @Test
    void thePencilOnTheMeasurementsLeadsHere(@Autowired BrowserlessUIContext ui) {
        ui.navigate(DashboardView.class, "PENC");

        pencil(ui).click();

        assertTrue(ui.findSpan().withTextContaining("Device ID: PENC").exists(),
                "The pencil should open the settings screen for this device");
    }

    /*
       The whole journey: save a name, land back on the measurements, and see the
       name where the identifier was — in the same header whose pencil started it.
    */
    @Test
    void aSavedNameReplacesTheIdentifierOnTheMeasurements(@Autowired BrowserlessUIContext ui) {
        ui.navigate(DeviceSettingsView.class, "MOKI");
        ui.findTextField().withLabel("Name").setValue("Mökin sauna");

        ui.findButton().withText("Save").click();

        assertEquals("Mökin sauna", settings.nameFor("MOKI"));
        SubViewHeader header = ui.find(SubViewHeader.class).first();
        assertTrue(header.getChildren().anyMatch(child ->
                        child instanceof H1 h1 && h1.getText().equals("Mökin sauna")),
                "Saving should land back on the measurements, titled by the new name");
    }

    /* Emptying the field is how a name is taken back. */
    @Test
    void clearingTheNameBringsTheIdentifierBack(@Autowired BrowserlessUIContext ui) {
        settings.rename("CLR1", "Wrong name");
        ui.navigate(DeviceSettingsView.class, "CLR1");
        assertEquals("Wrong name", ui.findTextField().withLabel("Name").component().getValue(),
                "The field should open showing what is stored");

        ui.findTextField().withLabel("Name").setValue("");
        ui.findButton().withText("Save").click();

        assertNull(settings.nameFor("CLR1"));
    }

    /*
       The other choice on this screen: the featured image. A bundled painting
       is stored as the address it ships at, and Save carries it to the store.
    */
    @Test
    void aBundledPaintingIsStoredByItsAddress(@Autowired BrowserlessUIContext ui) {
        ui.navigate(DeviceSettingsView.class, "SAUN");
        imageField(ui).setValue("media/buildings/sauna.webp");

        ui.findButton().withText("Save").click();

        assertEquals("media/buildings/sauna.webp", settings.imageUrlFor("SAUN"));
    }

    /*
       The user's own picture travels the same way: as an address. This is the
       whole of the custom-URL feature — no upload, only remembering where.
    */
    @Test
    void aCustomUrlIsStoredAsGiven(@Autowired BrowserlessUIContext ui) {
        ui.navigate(DeviceSettingsView.class, "CUST");
        imageField(ui).setValue("https://example.com/oma-kuva.jpg");

        ui.findButton().withText("Save").click();

        assertEquals("https://example.com/oma-kuva.jpg", settings.imageUrlFor("CUST"));
    }

    /* "No image" is a choice too, and it has to be able to undo the others. */
    @Test
    void theImageCanBeTakenOffAgain(@Autowired BrowserlessUIContext ui) {
        settings.setImageUrl("BARE", "media/buildings/barn.webp");
        ui.navigate(DeviceSettingsView.class, "BARE");
        assertEquals("media/buildings/barn.webp", imageField(ui).getValue(),
                "The field should open showing what is stored");

        imageField(ui).setValue(null);
        ui.findButton().withText("Save").click();

        assertNull(settings.imageUrlFor("BARE"));
    }

    private static FeaturedImageField imageField(BrowserlessUIContext ui) {
        return ui.find(FeaturedImageField.class).first();
    }

    /*
       The pencil is an icon alone, so it is found the way assistive technology
       finds it: by its accessible name.
    */
    private static Button pencil(BrowserlessUIContext ui) {
        return ui.find(Button.class).all().stream()
                .filter(button -> "Device settings"
                        .equals(button.getElement().getAttribute("aria-label")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No pencil in the header"));
    }
}
