package fi.mstahv.sensorhub.ui;

import java.util.List;

import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.flow.component.HasComponents;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fi.mstahv.sensorhub.store.ClientDeviceStore;
import fi.mstahv.sensorhub.store.DeviceSettingsStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The button a freshly built device is offered with. Tested on its own rather
 * than through the build page, which a server without a toolchain — every test
 * server — does not show.
 *
 * <p>It is dropped into whatever view is open, because the browserless harness
 * gives a component a UI to navigate from only once it is attached somewhere.
 */
@UiTest
class AddToDevicesTest {

    private static final String TOKEN = "a-browser-that-just-built-a-device";

    @Autowired
    private ClientDeviceStore clientDevices;

    @Autowired
    private DeviceSettingsStore deviceSettings;

    @Test
    void addingPutsTheDeviceOnTheListAndOpensIt(@Autowired BrowserlessUIContext ui) {
        show(ui, "NEW1");

        ui.findButton().withText("Add NEW1 to my devices").click();

        assertEquals(List.of("NEW1"), clientDevices.devicesFor(TOKEN));
        assertInstanceOf(DashboardView.class, ui.getCurrentView(),
                "the device's own page is where the first packet will land");
        assertNotNull(deviceSettings.imageUrlFor("NEW1"),
                "a new card should not be the one blank thing on the front page");
    }

    @Test
    void aDeviceAlreadyOnTheListIsOpenedRatherThanAddedAgain(@Autowired BrowserlessUIContext ui) {
        clientDevices.add(TOKEN, "OLD1");
        show(ui, "OLD1");

        ui.findButton().withText("Open OLD1").click();

        assertEquals(List.of("OLD1"), clientDevices.devicesFor(TOKEN), "no duplicate");
        assertInstanceOf(DashboardView.class, ui.getCurrentView());
    }

    @Test
    void theButtonIsPrimary(@Autowired BrowserlessUIContext ui) {
        show(ui, "NEW2");

        var button = ui.findButton().withText("Add NEW2 to my devices").component();
        assertTrue(button.getThemeNames().contains("primary"),
                "on this page it is the thing to do next");
    }

    private void show(BrowserlessUIContext ui, String deviceId) {
        ui.navigate(DeviceListView.class);
        Browser.answerStorageWith(ui.getUI(), TOKEN);
        ((HasComponents) ui.getCurrentView()).add(
                new AddToDevices(deviceId, clientDevices, deviceSettings, () -> TOKEN));
    }
}
