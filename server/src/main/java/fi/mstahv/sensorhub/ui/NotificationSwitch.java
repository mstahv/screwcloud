package fi.mstahv.sensorhub.ui;

import java.util.function.Supplier;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import fi.mstahv.sensorhub.alerts.WebPushService;

/**
 * One switch for all notifications to this browser.
 *
 * <p>Turning it off removes the browser's push subscription, which stops every
 * notification at once regardless of what any sensor is subscribed to. The
 * per-sensor choices are kept, so turning it back on does not mean configuring
 * them again.
 *
 * <p>The browser is the authority on the current state, not the database: someone
 * can revoke notification permission in browser settings without this server
 * hearing about it, and a switch that read only its own table would then sit on
 * "on" while nothing ever arrived.
 */
class NotificationSwitch extends VerticalLayout {

    private final WebPushService webPush;
    private final Supplier<String> clientId;
    private final Checkbox toggle = new Checkbox("Temperature alerts on this browser");
    private final Span hint = new Span();

    NotificationSwitch(WebPushService webPush, Supplier<String> clientId) {
        this.webPush = webPush;
        this.clientId = clientId;

        setPadding(false);
        setSpacing(false);

        hint.getStyle().setFontSize("0.75rem");
        hint.getStyle().setColor("var(--vaadin-text-color-secondary)");

        /*
           isFromClient() matters: reading the state back from the browser sets
           the value programmatically, and without this that would immediately be
           mistaken for the user asking to subscribe.
        */
        toggle.addValueChangeListener(event -> {
            if (event.isFromClient()) {
                apply(event.getValue());
            }
        });

        add(toggle, hint);

        /*
           Shown even when the server has no VAPID keys, just disabled and saying
           why. Hiding it was worse: the feature then looked like it did not exist,
           and the only clue was a line in the startup log.
        */
        if (!webPush.isEnabled()) {
            toggle.setEnabled(false);
            hint.setText("Notifications are not configured on this server. "
                    + "Set the VAPID keys to enable them — see the README.");
        }
    }

    /**
     * Reads the current state from the browser. Called once the browser token is
     * known, since acting on the switch needs it.
     */
    void refresh(UI ui) {
        if (!webPush.isEnabled()) {
            return;
        }
        webPush.isSubscribedInBrowser(ui, subscribed -> {
            toggle.setValue(subscribed);
            hint.setText(subscribed
                    ? "Alerts you have chosen per sensor will arrive as notifications."
                    : "Choose which alerts you want in each sensor's settings.");
        });
    }

    private void apply(boolean wanted) {
        String client = clientId.get();
        if (client == null) {
            // The token has not come back from the browser yet.
            toggle.setValue(!wanted);
            return;
        }
        UI ui = UI.getCurrent();
        if (wanted) {
            webPush.subscribe(ui, client, result -> {
                if (result.subscribed()) {
                    hint.setText("Alerts you have chosen per sensor will arrive as notifications.");
                    return;
                }
                /*
                   The switch springs back, and the reason goes both in the hint and
                   in a notification: the hint stays readable after the toast is
                   gone, and some of these need a step in browser settings that the
                   reader has to remember.
                */
                toggle.setValue(false);
                hint.setText(result.problem());
                Notification.show(result.problem(), 8000, Notification.Position.MIDDLE);
            });
        } else {
            webPush.unsubscribe(ui, client,
                    () -> hint.setText("Notifications are off. Your per-sensor choices are kept."));
        }
    }
}
