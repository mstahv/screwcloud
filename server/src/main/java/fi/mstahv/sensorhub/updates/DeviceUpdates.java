package fi.mstahv.sensorhub.updates;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.shared.Registration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Where a page says what it is looking at, and gets told when that changes.
 *
 * <p>The pages used to ask. Every open browser polled the server every five seconds,
 * which is twelve requests a minute each to be told, almost always, that nothing had
 * happened — while the packet that did change something sat on the server for up to
 * five seconds before anyone saw it. Both halves of that are wrong for data that
 * arrives when it arrives: the traffic is proportional to how many people are
 * looking rather than to how much is happening, and the delay is worst exactly when
 * somebody is standing next to a sensor waiting for their change to appear.
 *
 * <p>So the arrival pushes instead. A packet reaches {@link #arrived} on the UDP
 * thread, and the pages that said they care about that device are told from there. A
 * page nobody is looking at costs nothing, and a room full of people watching the
 * same device costs one wake-up each.
 *
 * <h2>What a view does</h2>
 *
 * <pre>{@code
 * updates.forDevice(this, deviceId, this::refresh);
 * }</pre>
 *
 * <p>The subscription ends when the view detaches, so that call needs no matching
 * {@code onDetach}. The returned {@link Registration} is for the other case — a view
 * that switches to another device while staying on screen has to drop the old
 * subscription itself.
 *
 * <h2>The notification says that, not what</h2>
 *
 * <p>The handler is told something changed and re-reads the store; the packet is not
 * handed to it. That is deliberate. A view needs more than the packet anyway — the
 * history behind each card, the sensor's settings, how long the device has been
 * quiet — so it would be reading the store regardless, and a version of the reading
 * that arrived by a second route is a version that can disagree with the stored one.
 * Two events therefore fit one handler: a packet arrived, and the sweep that decides
 * whether a device has gone quiet has run. A page cannot tell the difference and
 * does not need to; both mean "look again".
 *
 * <h2>What this class does so the views do not</h2>
 *
 * <p><b>It changes thread.</b> Handlers run through {@link UI#access}, holding the
 * session lock like any other UI code. A handler touching components straight from
 * the UDP thread would corrupt state slowly and unreproducibly, which is the worst
 * way for it to be wrong.
 *
 * <p><b>It forgets pages that have gone.</b> A browser can close between the packet
 * arriving and the update running; {@code access} then throws, and the subscription
 * is dropped rather than kept and retried forever.
 *
 * <p><b>It does not let a page take down the receiver.</b> One handler that throws
 * loses its own page's update and nothing else. The UDP thread has one job, and
 * losing it loses every device's data.
 */
@Service
public class DeviceUpdates {

    private static final Logger log = LoggerFactory.getLogger(DeviceUpdates.class);

    /**
     * The key for "any device at all", which is what the front page watches. It
     * cannot collide with a real subscription: a device identifier is one to four
     * characters, never none.
     */
    private static final String EVERY_DEVICE = "";

    /**
     * Subscribers by what they watch. A set per device rather than one list with a
     * filter, so a packet wakes the pages showing that device without walking past
     * the pages showing the other forty.
     */
    private final Map<String, Set<Subscriber>> byDevice = new ConcurrentHashMap<>();

    /** Told when this device reports, and when the silence sweep has run. */
    public Registration forDevice(Component view, String deviceId, Runnable onChange) {
        return subscribe(deviceId, view, onChange);
    }

    /**
     * Told when any device reports. What a list of all of them needs: a device
     * reporting for the first time is precisely the case where the page has no
     * identifier to have subscribed to.
     */
    public Registration forEveryDevice(Component view, Runnable onChange) {
        return subscribe(EVERY_DEVICE, view, onChange);
    }

    /**
     * A packet has been stored.
     *
     * <p>Called after the storing transaction rather than inside it: a page told
     * early reads the database and finds the previous packet, which looks exactly
     * like an update that did not work.
     */
    public void arrived(String deviceId) {
        wake(deviceId);
        wake(EVERY_DEVICE);
    }

    /**
     * The silence sweep has run, so a device may have gone quiet or come back.
     *
     * <p>This is the one thing that does not arrive as a packet — by definition,
     * since it is the absence of one. It is a server-side timer rather than a
     * browser one, which is the difference that matters: one sweep a minute serves
     * every open page, where the old poll cost every page a request every five
     * seconds to notice the same thing.
     */
    public void swept() {
        byDevice.keySet().forEach(this::wake);
    }

    /** How many pages are listening. For the tests, and for a log line worth having. */
    public int subscriberCount() {
        return byDevice.values().stream().mapToInt(Set::size).sum();
    }

    private Registration subscribe(String key, Component view, Runnable onChange) {
        UI ui = view.getUI().orElseThrow(() -> new IllegalStateException(
                "Subscribe from onAttach or later: a view that is not attached has no UI to "
                + "push through, and the subscription would never deliver anything."));

        Subscriber subscriber = new Subscriber(ui, onChange);
        byDevice.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(subscriber);

        /*
           Removed on detach as well as by the caller. Navigating away is how a
           subscription usually ends, and a view that had to remember to unsubscribe
           would leak one entry per page view — invisible while developing, and a
           slow leak on a server that stays up for months.
        */
        Registration onDetach = view.addDetachListener(event -> remove(key, subscriber));
        return () -> {
            onDetach.remove();
            remove(key, subscriber);
        };
    }

    private void remove(String key, Subscriber subscriber) {
        byDevice.computeIfPresent(key, (ignored, subscribers) -> {
            subscribers.remove(subscriber);
            /*
               The empty set goes with the last subscriber, or a server that has seen
               a thousand devices keeps a thousand empty sets — and swept() would
               walk all of them once a minute.
            */
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    private void wake(String key) {
        Set<Subscriber> subscribers = byDevice.get(key);
        if (subscribers == null) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.ui().access(() -> subscriber.onChange().run());
            } catch (UIDetachedException e) {
                // The browser went away between the packet arriving and this line.
                remove(key, subscriber);
            } catch (RuntimeException e) {
                log.warn("Updating a page watching '{}' failed: {}", key, e.getMessage());
            }
        }
    }

    /**
     * One page's interest. The UI rather than the view, because the UI is what the
     * update has to run through and what knows whether the browser is still there.
     */
    private record Subscriber(UI ui, Runnable onChange) {
    }
}
