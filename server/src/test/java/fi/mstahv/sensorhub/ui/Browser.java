package fi.mstahv.sensorhub.ui;

import java.util.List;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;

import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Answers on behalf of the browser that a browserless test does not have.
 *
 * <p>Almost nothing needs this — components run for real and their state is
 * readable. One thing does: {@link ClientId} identifies a browser by a token in
 * localStorage, and reading localStorage is a round trip. Flow sends the script and
 * waits; with no browser at the other end the callback never fires, the views never
 * learn their token, and a device list stays empty forever for reasons that have
 * nothing to do with the code under test.
 *
 * <p>So the test plays the browser: it completes the pending script with the value a
 * real localStorage would have returned. Everything downstream — the callback, the
 * refresh, the query — then runs exactly as in production.
 */
final class Browser {

    private Browser() {
    }

    /** What WebStorage.getItem sends; see WebStorage#requestItem. */
    private static final String STORAGE_READ = "getItem";

    /**
     * Answers the storage read Flow is waiting on with the given value.
     *
     * @param storedValue what localStorage holds, or null for a browser visiting
     *        for the first time — in which case {@link ClientId} generates a token
     *        and writes it back
     * @return how many reads were answered, so a test can tell that it actually
     *         played its part rather than silently doing nothing
     */
    static int answerStorageWith(UI ui, String storedValue) {
        List<PendingJavaScriptInvocation> pending =
                ui.getInternals().dumpPendingJavaScriptInvocations();

        List<PendingJavaScriptInvocation> reads = pending.stream()
                /*
                   Only the ones somebody is waiting on, and only the storage
                   reads. Completing a fire-and-forget script — setItem, or the
                   gauge's own styling call — throws, because there is no success
                   handler to hand a value to.
                */
                .filter(PendingJavaScriptInvocation::isSubscribed)
                .filter(invocation ->
                        invocation.getInvocation().getExpression().contains(STORAGE_READ))
                .toList();

        reads.forEach(invocation -> invocation.complete(storedValue == null
                ? JsonNodeFactory.instance.nullNode()
                : JsonNodeFactory.instance.textNode(storedValue)));
        return reads.size();
    }

    /** A browser with nothing stored yet, which is the common case in a test. */
    static void answerAsFirstVisit(UI ui) {
        answerStorageWith(ui, null);
    }
}
