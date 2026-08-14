package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.flow.component.button.Button;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The front page, driven the way a reader drives it: type an identifier, press Add,
 * see a card.
 */
@UiTest
class DeviceListViewTest {

    private static final String EMPTY_STATE = "No devices yet — add one below.";

    /*
       The reading order is the point of the layout, so it is asserted rather than
       left to whoever edits the constructor next: the devices, then adding one,
       then this browser's settings. It was once heading, form, switch, devices —
       a heading over a form, and a per-browser setting in the middle of content.
    */
    @Test
    void thePageReadsAsContentThenActionThenSettings(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        List<String> regions = ((com.vaadin.flow.component.Component) ui.getCurrentView())
                .getChildren()
                .map(child -> child.getClass().getSimpleName())
                .toList();

        assertEquals(List.of("BrandHeader", "Devices", "AddDevice", "BrowserSettings"), regions);
    }

    /* The heading has to sit with the devices it names, not with a form. */
    @Test
    void theHeadingAndTheDevicesAreOneRegion(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);
        add(ui, "LAHT");

        var devicesRegion = ((com.vaadin.flow.component.Component) ui.getCurrentView())
                .getChildren()
                .filter(child -> child.getClass().getSimpleName().equals("Devices"))
                .findFirst()
                .orElseThrow();

        assertTrue(Slots.deepFind(devicesRegion, DeviceLinkCard.class).isPresent(),
                "The devices belong under their own heading");
        assertTrue(devicesRegion.getChildren()
                        .anyMatch(child -> child instanceof com.vaadin.flow.component.html.H2),
                "and that heading belongs in the same region");
    }

    @Test
    void aDeviceCanBeAddedAndIsThenListed(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        add(ui, "LAHT");

        assertEquals(List.of("LAHT"), listedDevices(ui));
    }

    @Test
    void theEmptyStateGivesWayToTheFirstDevice(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);
        assertTrue(ui.findSpan().withText(EMPTY_STATE).exists(),
                "A browser with no devices should be told what to do");

        add(ui, "LAHT");

        // The locators only see what is visible, so gone from them is gone from view.
        assertFalse(ui.findSpan().withText(EMPTY_STATE).exists());
    }

    /*
       A page nobody has typed on yet has nothing to complain about. Asking for the
       identifier is the required indicator's job; saying it is missing before the
       reader has reached the field is the form telling them off for its own empty
       state — and on this page that error was the first thing on a first visit.
    */
    @Test
    void theFormDoesNotOpenComplaining(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        var field = ui.findTextField().withPlaceholder("Device ID").component();
        assertFalse(field.isInvalid(), "nothing has been typed, so nothing is wrong yet");
        assertTrue(field.isRequiredIndicatorVisible(), "and this is what asks for it");
    }

    /*
       Once they have filled it in, though, emptying it again is their change and
       is reported — otherwise a disabled Add button is all they get.
    */
    @Test
    void clearingTheIdentifierAgainIsReported(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        ui.findTextField().withPlaceholder("Device ID").setValue("LAHT");
        ui.findTextField().withPlaceholder("Device ID").setValue("");

        var field = ui.findTextField().withPlaceholder("Device ID").component();
        assertTrue(field.isInvalid(), "a value the reader removed is a mistake worth naming");
        assertEquals("Give the device identifier", field.getErrorMessage());
    }

    /*
       The identifier is four characters in the protocol, and the same constraint
       that says so on every table says it here. The reader finds that out at the
       field rather than from a toast after pressing Add — which is also why Add is
       not offered at all while the field cannot be used.
    */
    @Test
    void anImpossibleIdentifierIsRefusedAtTheField(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        ui.findTextField().withPlaceholder("Device ID").setValue("TOOLONG");

        var field = ui.findTextField().withPlaceholder("Device ID").component();
        assertTrue(field.isInvalid(), "The field should say the identifier cannot exist");
        assertEquals("A device identifier is 1 to 4 letters or digits", field.getErrorMessage());
        assertFalse(ui.findButton().withText("Add").component().isEnabled());
        assertTrue(listedDevices(ui).isEmpty());
    }

    /*
       The device sends its identifier in upper case, so "laht" and "LAHT" must not
       become two devices.
    */
    @Test
    void anIdentifierIsNormalisedToUpperCase(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        add(ui, "laht");

        assertEquals(List.of("LAHT"), listedDevices(ui));
    }

    @Test
    void aDeviceCanBeRemovedAgain(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);
        add(ui, "LAHT");

        /*
           The remove button is in the card's header suffix slot, which the locators
           do not traverse; see Slots.
        */
        Slots.require(ui.find(DeviceLinkCard.class).first(), Button.class).click();

        assertTrue(listedDevices(ui).isEmpty());
        assertTrue(ui.findSpan().withText(EMPTY_STATE).exists());
    }

    /*
       Without VAPID keys the server cannot send anything. The switch stays visible
       and says so — hiding it is how the whole feature once looked missing.
    */
    @Test
    void theNotificationSwitchExplainsItselfWhenPushIsNotConfigured(
            @Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        var toggle = ui.findCheckbox().withLabel("Notifications on this browser");

        assertTrue(toggle.exists(), "The switch should be visible even when unconfigured");
        assertFalse(toggle.component().isEnabled(), "It cannot be used without VAPID keys");
        assertTrue(ui.findSpan()
                .withCondition(span -> span.getText().contains("not configured on this server"))
                .exists());
    }

    /*
       The subscription is per browser and per device, and it starts off — adding a
       device does not sign anyone up for anything.
    */
    @Test
    void silenceAlertsStartOffAndCanBeSwitchedOn(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);
        add(ui, "LAHT");

        var toggle = ui.findCheckbox().withLabel("Notify if it stops reporting");
        assertFalse(toggle.component().getValue());

        /*
           Clicked rather than set: the toggle only acts on changes that came from
           the client, so a programmatic setValue would be ignored — exactly as it
           is in the browser when the server writes the state back.
        */
        toggle.click();

        assertTrue(ui.findCheckbox().withLabel("Notify if it stops reporting")
                .component().getValue());
        assertTrue(ui.findNotification().exists(), "The choice should be confirmed");
    }

    private static void openFrontPage(BrowserlessUIContext ui) {
        ui.navigate(DeviceListView.class);
        /*
           The view identifies the browser by a token in localStorage, which is a
           round trip. Nothing else happens until it is answered.
        */
        Browser.answerAsFirstVisit(ui.getUI());
    }

    private static void add(BrowserlessUIContext ui, String deviceId) {
        ui.findTextField().withPlaceholder("Device ID").setValue(deviceId);
        ui.findButton().withText("Add").click();
    }

    /** The device identifiers as the reader sees them, from the cards' title slots. */
    private static List<String> listedDevices(BrowserlessUIContext ui) {
        return ui.find(DeviceLinkCard.class).all().stream().map(Slots::titleOf).toList();
    }
}
