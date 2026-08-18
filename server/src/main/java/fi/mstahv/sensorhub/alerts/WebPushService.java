package fi.mstahv.sensorhub.alerts;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.webpush.WebPush;
import com.vaadin.flow.server.webpush.WebPushException;
import com.vaadin.flow.server.webpush.WebPushKeys;
import com.vaadin.flow.server.webpush.WebPushMessage;
import com.vaadin.flow.server.webpush.WebPushSubscription;

import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.PushSubscription;
import jakarta.annotation.PreDestroy;

/**
 * Web push, wrapped so the rest of the application does not have to care whether
 * it is configured.
 *
 * <p>Without VAPID keys this is inert: {@link #isEnabled()} returns false, the UI
 * hides the switch, and nothing else changes. That matters because the keys are a
 * credential — a deployment that has not generated a pair should still run.
 *
 * <p>Subscribing needs a UI, because the browser is the one that creates the
 * subscription and grants the permission. Sending does not: it is an HTTP request
 * from this server to the browser vendor's push service, which is why a
 * notification can be sent from the UDP thread with nobody logged in.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    private final AlertSubscriptionStore subscriptions;
    private final String publicKey;
    private final WebPush webPush;

    /*
       Sending is a network call to a third party, and the caller is the thread
       that receives measurements. One background thread keeps a slow or
       unreachable push service from delaying the next packet, and one is enough
       for a handful of notifications an hour.
    */
    private final ExecutorService sender =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "web-push-sender");
                thread.setDaemon(true);
                return thread;
            });

    WebPushService(AlertSubscriptionStore subscriptions,
                   @Value("${sensorhub.webpush.public-key}") String publicKey,
                   @Value("${sensorhub.webpush.private-key}") String privateKey,
                   @Value("${sensorhub.webpush.subject}") String subject) {
        this.subscriptions = subscriptions;
        this.publicKey = publicKey;

        if (publicKey.isBlank() || privateKey.isBlank() || subject.isBlank()) {
            this.webPush = null;
            log.info("Web push notifications are disabled: set VAPID_PUBLIC_KEY, "
                    + "VAPID_PRIVATE_KEY and VAPID_SUBJECT to enable them");
        } else {
            this.webPush = new WebPush(publicKey, privateKey, subject);
            log.info("Web push notifications are enabled");
        }
    }

    /** Whether this server has VAPID keys and can send notifications at all. */
    public boolean isEnabled() {
        return webPush != null;
    }

    /**
     * The result of asking a browser to subscribe.
     *
     * @param subscribed whether the browser is now subscribed
     * @param problem what went wrong, in words a reader can act on, or null on
     *        success
     */
    public record SubscribeResult(boolean subscribed, String problem) {
    }

    /**
     * Asks the browser to subscribe, which is what triggers the permission
     * prompt, and stores the result.
     *
     * <p>Calls the client side of Vaadin's web push support directly rather than
     * {@code WebPush.subscribe(ui, receiver)}. That method installs its own error
     * handler which unconditionally throws, so every ordinary browser side outcome
     * — permission denied, no service worker registered, an iOS browser that wants
     * the site on the home screen first — arrives as an "Unexpected error" dialog
     * and a stack trace in the server log. Refusing a permission prompt is a
     * normal thing for someone to do, not a server fault, and the reader deserves
     * to be told which of those it was.
     */
    public void subscribe(UI ui, String clientId, java.util.function.Consumer<SubscribeResult> onDone) {
        requireEnabled();
        ui.getPage()
                .executeJs("return window.Vaadin.Flow.webPush.subscribe($0)", publicKey)
                .then(json -> onDone.accept(store(clientId, json)),
                        error -> onDone.accept(new SubscribeResult(false, explain(error))));
    }

    private SubscribeResult store(String clientId, JsonNode subscription) {
        if (subscription == null || !subscription.has("endpoint")) {
            return new SubscribeResult(false, "The browser did not return a subscription.");
        }
        JsonNode keys = subscription.get("keys");
        subscriptions.subscribeToPush(clientId,
                subscription.get("endpoint").asString(),
                keys.get("p256dh").asString(),
                keys.get("auth").asString());
        return new SubscribeResult(true, null);
    }

    /*
       The client throws plain Errors with English messages. Mapping the two that
       have a remedy is worth more than passing the raw text through: the reader
       can do something about both, but only if told what.
    */
    private static String explain(String jsError) {
        String error = jsError == null ? "" : jsError;
        if (error.contains("blocked notifications")) {
            return "This browser has blocked notifications for the site. "
                    + "Allow them in its site settings and try again.";
        }
        if (error.contains("registration from service worker")) {
            return "This browser has no service worker for the site yet. "
                    + "On iOS and iPadOS, add the site to the home screen first; "
                    + "in Safari on a Mac, add it to the Dock. Otherwise reload and try again.";
        }
        return "The browser refused the subscription: " + error;
    }

    /**
     * Unsubscribes the browser and forgets its endpoint. The per-sensor choices
     * are deliberately left alone, so switching notifications back on restores
     * them.
     */
    public void unsubscribe(UI ui, String clientId, Runnable onDone) {
        requireEnabled();
        /*
           Own error handling for the same reason as subscribing — and the endpoint
           is forgotten either way. A browser that cannot tell us whether it
           unsubscribed is a browser we should stop sending to.
        */
        ui.getPage().executeJs("return window.Vaadin.Flow.webPush.unsubscribe()")
                .then(json -> {
                    subscriptions.unsubscribeFromPush(clientId);
                    onDone.run();
                }, error -> {
                    log.info("The browser could not unsubscribe ({}), "
                            + "forgetting its endpoint anyway", error);
                    subscriptions.unsubscribeFromPush(clientId);
                    onDone.run();
                });
    }

    /**
     * What a browser is able to do about notifications, which is not always
     * "everything" and not always "nothing".
     */
    public enum BrowserSupport {
        /** A subscription can be made. */
        AVAILABLE,
        /**
         * Plain HTTP somewhere other than localhost. There is no service worker at
         * all, which is every "let me open this from my phone on the LAN" moment.
         */
        NEEDS_SECURE_CONNECTION,
        /**
         * A secure page, but no service worker registered for it — the thing that
         * receives a notification when the tab is closed.
         */
        NO_SERVICE_WORKER,
        /**
         * A service worker without a push manager, which is Safari: it offers web
         * push only to a site the reader has added to the Dock or the home screen.
         */
        NOT_OFFERED_BY_BROWSER
    }

    /**
     * What this browser can do, asked before anything else is.
     *
     * <p>The rest of the web push API assumes the answer is "everything" and fails
     * loudly when it is not: {@code registrationStatus} reaches for
     * {@code registration.pushManager}, and where that does not exist the browser
     * throws a {@code TypeError} which arrives on the server as
     *
     * <pre>Unexpected error: Unable to execute web push command. JS error is
     * 'TypeError: undefined is not an object'</pre>
     *
     * <p>— a stack trace, logged at ERROR, about a browser that simply does not do
     * this. Both ways of getting there are ordinary rather than exceptional, and
     * they need different things said to the reader, which is why this answers with
     * a reason rather than a boolean.
     *
     * <p>The checks run in the browser, in the order that a failing one makes the
     * next meaningless. Nothing here depends on whether this server has keys — that
     * is a separate answer, {@link #isEnabled()}, and the caller decides which of the
     * two it wants to say first.
     */
    public void checkBrowserSupport(UI ui, java.util.function.Consumer<BrowserSupport> onKnown) {
        ui.getPage().executeJs(
                        "if (!window.isSecureContext) return 'NEEDS_SECURE_CONNECTION';"
                        + "if (!navigator.serviceWorker) return 'NO_SERVICE_WORKER';"
                        + "return navigator.serviceWorker.getRegistration().then(r =>"
                        + "  !r ? 'NO_SERVICE_WORKER'"
                        + "  : !r.pushManager ? 'NOT_OFFERED_BY_BROWSER'"
                        + "  : 'AVAILABLE');")
                .then(String.class, answer -> onKnown.accept(BrowserSupport.valueOf(answer)));
    }

    /**
     * Whether this browser currently has a push subscription according to the
     * browser itself.
     *
     * <p>The browser is the authority, not the database: notifications can be
     * revoked in browser settings without this server hearing about it, and the
     * switch would then show on while nothing would ever arrive.
     *
     * <p>Only ask after {@link #checkBrowserSupport} has answered
     * {@link BrowserSupport#AVAILABLE}.
     */
    public void isSubscribedInBrowser(UI ui, java.util.function.Consumer<Boolean> onKnown) {
        if (!isEnabled()) {
            onKnown.accept(false);
            return;
        }
        webPush.subscriptionExists(ui, onKnown::accept);
    }

    /** The VAPID public key, exposed for diagnostics rather than for the browser. */
    public String getPublicKey() {
        return publicKey;
    }

    /**
     * Sends one notification, on a background thread. Failures are logged and
     * endpoints the push service has given up on are forgotten.
     */
    public void send(PushSubscription subscription, String title, String body) {
        if (!isEnabled()) {
            return;
        }
        sender.execute(() -> deliver(subscription, title, body));
    }

    private void deliver(PushSubscription subscription, String title, String body) {
        try {
            webPush.sendNotification(
                    new WebPushSubscription(subscription.getEndpoint(),
                            new WebPushKeys(subscription.getP256dh(), subscription.getAuth())),
                    new WebPushMessage(title, body));
        } catch (WebPushException e) {
            /*
               Vaadin does not expose the HTTP status, only a message that names
               404 and 410 together. Both mean the same thing here — the endpoint
               is gone for good — and matching the message is the only signal
               available. Getting it wrong costs a subscription that the browser
               will recreate on its next visit, which is why this errs towards
               forgetting.
            */
            if (e.getMessage() != null && e.getMessage().contains("404 or 410")) {
                log.info("Push endpoint is gone, forgetting it: {}", shortEndpoint(subscription));
                subscriptions.forgetEndpoint(subscription.getEndpoint());
            } else {
                log.warn("Sending a notification to {} failed: {}",
                        shortEndpoint(subscription), e.getMessage());
            }
        }
    }

    /*
       Endpoints are long and contain the delivery token. Logging the tail of one
       is enough to tell two apart without writing a credential into the log.
    */
    private static String shortEndpoint(PushSubscription subscription) {
        String endpoint = subscription.getEndpoint();
        return endpoint.length() <= 12 ? endpoint : "…" + endpoint.substring(endpoint.length() - 8);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("Web push is not configured");
        }
    }

    @PreDestroy
    void shutdown() {
        sender.shutdownNow();
    }
}
