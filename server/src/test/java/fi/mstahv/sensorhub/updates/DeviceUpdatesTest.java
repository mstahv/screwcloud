package fi.mstahv.sensorhub.updates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The subscription bookkeeping, which is the part of pushing that goes wrong
 * quietly: a page that is told nothing looks like a page with nothing to say, and a
 * page that is still subscribed after it closed is a leak nobody sees until the
 * server has been up for a month.
 *
 * <p>A real UI, because that is what the class is about — it holds UIs, runs
 * handlers through them, and drops them when they go.
 */
class DeviceUpdatesTest {

    private static final String DEVICE = "LAHT";

    private final DeviceUpdates updates = new DeviceUpdates();
    private final AtomicInteger woken = new AtomicInteger();

    private final VerticalLayout view = new VerticalLayout();

    private BrowserlessUIContext ui;

    @BeforeEach
    void setUp() {
        ui = BrowserlessUIContext.forComponent(view);
    }

    @AfterEach
    void tearDown() {
        ui.close();
    }

    /**
     * Publishes the way the UDP thread does, from outside the session lock, and then
     * lets the queued work run — which is what the push connection does for real.
     * Calling the handler straight would test nothing: crossing that boundary is the
     * job.
     */
    private void publish(Runnable event) {
        event.run();
        /*
           UI.access queues rather than runs: the work belongs to whoever holds the
           session lock, and here that is this test for its whole length. In
           production the push connection's thread picks the queue up on unlocking;
           here it has to be asked, or every assertion below would read the state
           from before the event.
        */
        VaadinSession session = ui.getUI().getSession();
        session.getService().runPendingAccessTasks(session);
    }

    @Test
    void aPageWatchingADeviceIsWokenWhenItReports() {
        updates.forDevice(view, DEVICE, woken::incrementAndGet);

        publish(() -> updates.arrived(DEVICE));

        assertEquals(1, woken.get());
    }

    /** And not by the other forty devices, which is the point of the key. */
    @Test
    void aPageIsNotWokenByAnotherDevice() {
        updates.forDevice(view, DEVICE, woken::incrementAndGet);

        publish(() -> updates.arrived("SLP1"));

        assertEquals(0, woken.get());
    }

    /**
     * The front page has no one device to watch: a device reporting for the first
     * time is exactly the case where it has nothing to have subscribed to.
     */
    @Test
    void aPageWatchingEverythingIsWokenByAnyDevice() {
        updates.forEveryDevice(view, woken::incrementAndGet);

        publish(() -> updates.arrived("SLP1"));
        publish(() -> updates.arrived(DEVICE));

        assertEquals(2, woken.get());
    }

    /**
     * The sweep is the only change no packet announces — it is the absence of one —
     * so a page that shows whether a device has gone quiet has to hear about it.
     */
    @Test
    void everyPageIsWokenBySweep() {
        updates.forDevice(view, DEVICE, woken::incrementAndGet);
        updates.forEveryDevice(view, woken::incrementAndGet);

        publish(updates::swept);

        assertEquals(2, woken.get());
    }

    /**
     * Detaching is how a subscription usually ends, and the reason views do not have
     * to unsubscribe. Without this, every page view would leave an entry behind — an
     * invisible fault while developing and a slow leak on a server that stays up.
     */
    @Test
    void aDetachedPageIsForgotten() {
        updates.forDevice(view, DEVICE, woken::incrementAndGet);

        view.removeFromParent();

        assertEquals(0, updates.subscriberCount());
        publish(() -> updates.arrived(DEVICE));
        assertEquals(0, woken.get());
    }

    /** And a view that switches device drops the old subscription through this. */
    @Test
    void aRegistrationCanBeRemovedWhileThePageStays() {
        Registration registration = updates.forDevice(view, DEVICE, woken::incrementAndGet);

        registration.remove();

        assertEquals(0, updates.subscriberCount());
        publish(() -> updates.arrived(DEVICE));
        assertEquals(0, woken.get());
    }

    /**
     * Subscribing from a constructor is the mistake this stops. There is no UI to
     * push through before the view is attached, so the subscription would be made,
     * kept, and never deliver anything — a page that silently stops updating, which
     * is the failure this whole class exists to avoid.
     */
    @Test
    void subscribingBeforeAttachIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> updates.forDevice(new Span(), DEVICE, woken::incrementAndGet));
    }

    /**
     * One page's broken handler must not cost the others their update. The thread
     * this runs on receives every device's data, and it has nowhere to report to.
     */
    @Test
    void oneFailingPageDoesNotStopTheOthers() {
        updates.forDevice(view, DEVICE, () -> {
            throw new IllegalStateException("this page is having a bad day");
        });
        updates.forDevice(view, DEVICE, woken::incrementAndGet);

        publish(() -> updates.arrived(DEVICE));

        assertEquals(1, woken.get());
    }
}
