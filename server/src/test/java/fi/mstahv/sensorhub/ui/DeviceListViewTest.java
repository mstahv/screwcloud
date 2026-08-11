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

    private static final String EMPTY_STATE = "No devices yet. Add one by its identifier.";

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
       The identifier is four characters in the protocol and the store refuses
       anything longer. The reader should be told, not left with a button that did
       nothing.
    */
    @Test
    void anImpossibleIdentifierIsRefusedWithAnExplanation(@Autowired BrowserlessUIContext ui) {
        openFrontPage(ui);

        add(ui, "TOOLONG");

        assertTrue(ui.findNotification().exists(), "Expected a notification explaining the refusal");
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

        var toggle = ui.findCheckbox().withLabel("Temperature alerts on this browser");

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
