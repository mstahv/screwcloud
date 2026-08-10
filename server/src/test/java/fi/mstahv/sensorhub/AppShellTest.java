package fi.mstahv.sensorhub;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.PWA;

/**
 * Guards one setting whose failure mode is invisible.
 */
class AppShellTest {

    /*
       Flow registers the service worker only when offline is enabled, and
       actively unregisters it when it is not. Web push needs that service worker
       — it is what receives the notification and shows it — so offline = false
       turns notifications off in every browser, with no symptom until someone
       flips the switch and the browser answers "Cannot get registration from
       service worker".

       That is exactly what happened here once, which is why it is a test rather
       than a comment.
    */
    @Test
    void offlineStaysEnabledSoTheServiceWorkerIsRegistered() {
        PWA pwa = AppShell.class.getAnnotation(PWA.class);

        assertNotNull(pwa, "The app shell needs @PWA for notifications to work at all");
        assertTrue(pwa.offline(),
                "offline = false makes Flow unregister the service worker, "
                        + "and web push cannot work without one");
    }
}
