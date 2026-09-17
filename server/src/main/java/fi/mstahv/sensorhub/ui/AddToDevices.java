package fi.mstahv.sensorhub.ui;

import java.util.function.Supplier;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.notification.Notification;

import fi.mstahv.sensorhub.store.ClientDeviceStore;
import fi.mstahv.sensorhub.store.DeviceSettingsStore;

/**
 * The next step after a build: put the device on this browser's list and go
 * and look at it.
 *
 * <p>A device appears nowhere on its own. The front page shows the devices a
 * browser has asked for, and somebody who has just built and flashed one has
 * the identifier in front of them and the board in their hand — this is the
 * moment they know what it is called, and the moment they will otherwise walk
 * off to the front page and type the same four characters into a form. So the
 * button does what that form does, and then opens the device's own page, where
 * the first packet will land.
 *
 * <p>Adding is idempotent, so a device already on the list is simply opened; the
 * caption says which of the two is about to happen.
 *
 * <p>The browser token arrives by a round trip and may not be back yet on a page
 * this fresh, so it comes as a supplier read at the click rather than a value
 * fixed at construction.
 */
class AddToDevices extends Section {

    private final String deviceId;
    private final ClientDeviceStore clientDevices;
    private final DeviceSettingsStore deviceSettings;
    private final Supplier<String> clientId;

    AddToDevices(String deviceId, ClientDeviceStore clientDevices,
                 DeviceSettingsStore deviceSettings, Supplier<String> clientId) {
        this.deviceId = deviceId;
        this.clientDevices = clientDevices;
        this.deviceSettings = deviceSettings;
        this.clientId = clientId;

        add(new SectionHeading("Then follow it"),
                new Hint("The device turns up on your front page only once it is on your list. "
                        + "Add it now, and its first packet lands on a card that is already "
                        + "waiting for it."),
                new AddButton());
    }

    private boolean alreadyListed() {
        String token = clientId.get();
        return token != null && clientDevices.devicesFor(token).contains(deviceId);
    }

    private void addAndOpen() {
        String token = clientId.get();
        if (token == null) {
            // The token has not come back from the browser yet; rare, and brief.
            Notification.show("One moment — the page is still starting up. Try again.");
            return;
        }
        String added = clientDevices.add(token, deviceId);
        /*
           A picture, so the new card is not the one blank thing on the front page.
           The same choice the front page's own form makes; see DefaultFeaturedImage.
        */
        DefaultFeaturedImage.assignIfMissing(added, clientDevices.devicesFor(token), deviceSettings);
        getUI().ifPresent(ui -> ui.navigate(DashboardView.class, added));
    }

    /** Primary, because on this page it is the thing to do next. */
    private class AddButton extends Button {
        AddButton() {
            setText(alreadyListed()
                    ? "Open %s".formatted(deviceId)
                    : "Add %s to my devices".formatted(deviceId));
            addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            addClickListener(click -> addAndOpen());
        }
    }
}
