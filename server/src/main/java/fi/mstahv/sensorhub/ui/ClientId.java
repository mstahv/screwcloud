package fi.mstahv.sensorhub.ui;

import java.util.UUID;
import java.util.function.Consumer;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.page.WebStorage;
import com.vaadin.flow.component.page.WebStorage.Storage;

/**
 * The browser token that lets the device list be remembered without user
 * accounts.
 *
 * <p>The browser generates a random token on its first visit and stores it in
 * localStorage. This is not a security mechanism: the token is readable in the
 * browser, and knowing it grants nothing that knowing a device identifier would
 * not already grant. The point is only to remember choices.
 *
 * <p>localStorage rather than sessionStorage, so the list survives closing a
 * tab. Clearing browser data resets the list — an acceptable price for not
 * needing a login.
 */
final class ClientId {

    static final String STORAGE_KEY = "sensorhub.clientId";

    private ClientId() {
    }

    /**
     * Reads the token from the browser and creates one if absent. Calls
     * {@code onResolved} only once the value is known, because reading
     * WebStorage requires a round trip to the browser.
     *
     * <p>The UI is passed explicitly rather than relying on
     * {@code UI.getCurrent()}, since the call happens during attach and there is
     * no need to depend on thread-local state.
     */
    static void resolve(UI ui, Consumer<String> onResolved) {
        WebStorage.getItem(ui, Storage.LOCAL_STORAGE, STORAGE_KEY, stored -> {
            if (stored == null || stored.isBlank()) {
                String created = UUID.randomUUID().toString();
                WebStorage.setItem(ui, Storage.LOCAL_STORAGE, STORAGE_KEY, created);
                onResolved.accept(created);
            } else {
                onResolved.accept(stored);
            }
        });
    }
}
