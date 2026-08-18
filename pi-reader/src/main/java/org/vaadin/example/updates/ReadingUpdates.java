package org.vaadin.example.updates;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.shared.Registration;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * Where the page says it is open, and gets told when there is something new.
 *
 * <p>It used to ask instead, five times a minute, whether anything had changed. On a
 * page whose whole job is to show what a radio has just heard, that is both too
 * often and too late: a tag advertising every two seconds was shown up to five
 * seconds after it was heard, and the browser asked twelve times a minute even when
 * the radio was off.
 *
 * <p>Now the reading pushes. Whoever heard something calls {@link #changed}, and the
 * page — if anyone has it open — is updated from that thread.
 *
 * <h2>Bursts are collapsed</h2>
 *
 * <p>This is the difference from the server's version of the same idea, and the
 * reason this class is not shared with it. A device reports to the server every few
 * minutes; a room full of Ruuvi tags advertises several times a second, and a page
 * that redrew on each of those would be doing more work than the poll it replaced.
 *
 * <p>So a wake-up that is already queued is not queued twice. The flag is cleared as
 * the refresh starts, which is what makes the collapsing safe: everything heard
 * while a refresh was pending is in the registry by the time it reads it, and
 * anything heard after that sets the flag again. No timer, no interval to tune, and
 * a burst of forty advertisements costs one redraw rather than forty.
 *
 * <h2>What a view does</h2>
 *
 * <pre>{@code
 * updates.onChange(this, this::refresh);
 * }</pre>
 *
 * <p>The subscription ends when the view detaches. On this machine there is only one
 * page and it is usually the only one open, but a phone and a laptop both looking at
 * the Pi is exactly the case where the leak would otherwise be silent.
 */
@ApplicationScoped
public class ReadingUpdates {

    private static final Logger LOG = Logger.getLogger(ReadingUpdates.class);

    /**
     * How often the page is woken with nothing new to show it.
     *
     * <p>Not for the readings — those announce themselves — but for their absence: a
     * tag that has gone quiet is marked as such after a minute, and nothing arrives
     * to say so. Thirty seconds is half that, so the mark is never more than half a
     * minute late, and it is one timer on this machine rather than a request from
     * every browser looking at it.
     */
    private static final String SILENCE_CHECK = "30s";

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    /** Told whenever something new has been heard, and every half minute regardless. */
    public Registration onChange(Component view, Runnable onChange) {
        UI ui = view.getUI().orElseThrow(() -> new IllegalStateException(
                "Subscribe from onAttach or later: a view that is not attached has no UI to "
                + "push through, and the subscription would never deliver anything."));

        Subscriber subscriber = new Subscriber(ui, onChange);
        subscribers.add(subscriber);

        Registration onDetach = view.addDetachListener(event -> subscribers.remove(subscriber));
        return () -> {
            onDetach.remove();
            subscribers.remove(subscriber);
        };
    }

    /**
     * Something has been heard, or some state the page shows has moved.
     *
     * <p>Called from whichever thread did the hearing — the BLE scanner's, the
     * radio's, the uploader's. What it must not do is touch the page from there,
     * which is why the refresh is handed to the UI rather than run here.
     */
    public void changed() {
        subscribers.forEach(this::wake);
    }

    @Scheduled(every = SILENCE_CHECK)
    void checkForSilence() {
        changed();
    }

    /** How many pages are open on this machine. For the tests, and for the logs. */
    public int subscriberCount() {
        return subscribers.size();
    }

    private void wake(Subscriber subscriber) {
        /*
           Already queued: whatever caused this call will be in the registry before
           the pending refresh reads it, so a second refresh would draw the same
           thing twice.
        */
        if (!subscriber.pending().compareAndSet(false, true)) {
            return;
        }
        try {
            subscriber.ui().access(() -> {
                /*
                   Cleared first. Anything heard from here on is genuinely after this
                   refresh started reading, and has to be able to ask for another.
                */
                subscriber.pending().set(false);
                subscriber.onChange().run();
            });
        } catch (UIDetachedException e) {
            // The browser closed between hearing something and showing it.
            subscribers.remove(subscriber);
        } catch (RuntimeException e) {
            subscriber.pending().set(false);
            LOG.warnf("Updating the page failed: %s", e.getMessage());
        }
    }

    /**
     * One page's interest. The UI rather than the view, because the UI is what the
     * refresh has to run through and what knows whether the browser is still there.
     */
    private record Subscriber(UI ui, Runnable onChange, AtomicBoolean pending) {
        Subscriber(UI ui, Runnable onChange) {
            this(ui, onChange, new AtomicBoolean());
        }
    }
}
