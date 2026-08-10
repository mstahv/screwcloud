package fi.mstahv.sensorhub.ui;

import java.time.DateTimeException;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;

/**
 * The time zone of the browser that is looking at the page.
 *
 * <p>Measurements are stored as instants, so any absolute time shown has to be
 * rendered in some zone. The server's own zone is the wrong one: it says nothing
 * about where the person reading the page is, and a server in a container is
 * usually on UTC — which would put every timestamp a few hours off with no
 * indication that anything was wrong.
 *
 * <p>No round trip is needed for this. Vaadin collects the browser details during
 * UI initialization, so {@code getExtendedClientDetails()} answers immediately
 * and never returns null. (Its older sibling
 * {@code retrieveExtendedClientDetails}, the one with a callback, is deprecated
 * for exactly that reason.)
 */
final class ClientTimeZone {

    private static final Logger log = LoggerFactory.getLogger(ClientTimeZone.class);

    private ClientTimeZone() {
    }

    static ZoneId get() {
        /*
           No UI in unit tests and in background threads. The server's zone is
           then the only thing available, and for a test it is also the only thing
           that makes the expected values predictable.
        */
        UI ui = UI.getCurrent();
        if (ui == null) {
            return ZoneId.systemDefault();
        }

        /*
           The id is null on browsers without Intl support, which in practice
           means browsers older than roughly 2014. The offset in the same details
           object is not a better fallback: it is zero both for a browser
           genuinely on UTC and for details that have not been filled in yet, so
           trusting it would silently relabel every timestamp as UTC.
        */
        String zoneId = ui.getPage().getExtendedClientDetails().getTimeZoneId();
        if (zoneId == null) {
            return ZoneId.systemDefault();
        }

        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            // A zone the browser knows and this JVM's tzdata does not.
            log.warn("Browser reported an unknown time zone '{}', using {}",
                    zoneId, ZoneId.systemDefault());
            return ZoneId.systemDefault();
        }
    }
}
