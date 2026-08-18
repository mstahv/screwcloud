package org.vaadin.example.updates;

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
 * How the page is told, and — the part that earns its own class — how often.
 *
 * <p>A real UI, because holding one, running work through it and letting it go is
 * what this does.
 */
class ReadingUpdatesTest {

    private final ReadingUpdates updates = new ReadingUpdates();
    private final AtomicInteger refreshes = new AtomicInteger();

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

    @Test
    void anOpenPageIsToldWhenSomethingIsHeard() {
        updates.onChange(view, refreshes::incrementAndGet);

        heard();

        assertEquals(1, refreshes.get());
    }

    /**
     * The reason this class exists rather than a plain listener list. Between them,
     * the tags in a house advertise several times a second, and a page that redrew
     * on each would be doing more work than the five-second poll it replaced. A
     * refresh that has not run yet will read everything heard since it was asked
     * for, so asking again buys nothing.
     */
    @Test
    void aBurstOfReadingsCostsOneRefresh() {
        updates.onChange(view, refreshes::incrementAndGet);

        updates.changed();
        updates.changed();
        updates.changed();
        flush();

        assertEquals(1, refreshes.get());
    }

    /** And the next thing heard still gets its own refresh. */
    @Test
    void whatIsHeardAfterARefreshCausesAnother() {
        updates.onChange(view, refreshes::incrementAndGet);

        heard();
        heard();

        assertEquals(2, refreshes.get());
    }

    /**
     * A tag going quiet is the one thing that arrives as nothing at all, so the page
     * is woken on a timer as well — a timer on this machine rather than a request
     * from every browser looking at it.
     */
    @Test
    void theSilenceCheckWakesThePageToo() {
        updates.onChange(view, refreshes::incrementAndGet);

        updates.checkForSilence();
        flush();

        assertEquals(1, refreshes.get());
    }

    /** Closing the page ends the subscription, without the view doing anything. */
    @Test
    void aClosedPageIsForgotten() {
        updates.onChange(view, refreshes::incrementAndGet);

        view.removeFromParent();

        assertEquals(0, updates.subscriberCount());
        heard();
        assertEquals(0, refreshes.get());
    }

    @Test
    void aRegistrationCanBeRemovedWhileThePageStays() {
        Registration registration = updates.onChange(view, refreshes::incrementAndGet);

        registration.remove();

        assertEquals(0, updates.subscriberCount());
        heard();
        assertEquals(0, refreshes.get());
    }

    /**
     * Subscribing before the page is attached would be accepted, kept, and never
     * deliver anything — a page that silently stops updating, which is the exact
     * failure this class is here to prevent.
     */
    @Test
    void subscribingBeforeAttachIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> updates.onChange(new Span(), refreshes::incrementAndGet));
    }

    /** Heard on some other thread, then shown — which is two steps, as in production. */
    private void heard() {
        updates.changed();
        flush();
    }

    /**
     * Runs what UI.access queued. Work handed to a UI belongs to whoever holds the
     * session lock, and in a test that is the test itself for its whole length, so
     * nothing runs until it is asked to. In production the push connection's thread
     * does this when it unlocks.
     */
    private void flush() {
        VaadinSession session = ui.getUI().getSession();
        session.getService().runPendingAccessTasks(session);
    }
}
