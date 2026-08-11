package fi.mstahv.sensorhub.ui;

import java.time.Instant;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.OptionalParameter;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.aura.Aura;

import fi.mstahv.sensorhub.alerts.ConnectionMonitor;
import fi.mstahv.sensorhub.alerts.DeviceActivity;
import fi.mstahv.sensorhub.alerts.Elapsed;
import fi.mstahv.sensorhub.alerts.WebPushService;
import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.HeatSumCounterStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.SensorSettingsStore;
import org.vaadin.firitin.util.style.VaadinCssProps;

/**
 * One device's latest readings. The device comes from a URL parameter, for
 * example {@code /device/LAHT}, which makes the view linkable and bookmarkable.
 *
 * <p>The view does not check whether the device belongs to the caller's list:
 * knowing the address is enough. That is a deliberate choice — the device list is
 * a convenience, not access control.
 *
 * <p>Updates are polled rather than pushed: devices send every five minutes, so a
 * few seconds of latency is irrelevant and polling saves one thing to configure.
 */
@Route("device")
@StyleSheet(Aura.STYLESHEET)
public class DashboardView extends VerticalLayout
        implements HasUrlParameter<String>, HasDynamicTitle {

    private static final int POLL_INTERVAL_MS = 5000;

    private final MeasurementStore store;
    private final ConnectionMonitor connections;
    private final SensorCardLayout cards;
    private final H2 heading = new H2();
    private final Span deviceStatus = new Span();
    /** Shown only when the device is late; see DeviceActivity for what late means. */
    private final Badge offline = new Badge();
    private final Span emptyState = new Span();

    private Registration pollRegistration;
    private String deviceId;

    /** The last packet rendered, so an unchanged state can be skipped. */
    private Instant renderedReceivedAt;

    public DashboardView(MeasurementStore store, SensorSettingsStore settings,
                         AlertSubscriptionStore alerts, HeatSumCounterStore heatSums,
                         WebPushService webPush, ConnectionMonitor connections) {
        this.store = store;
        this.connections = connections;
        this.cards = new SensorCardLayout(store, settings, alerts, heatSums, webPush);
        offline.addThemeVariants(BadgeVariant.ERROR);
        offline.setVisible(false);
        setSizeFull();

        deviceStatus.getStyle().setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var());

        add(new RouterLink("← Devices", DeviceListView.class),
                heading, deviceStatus, offline, emptyState, cards);
    }

    /*
       Called before onAttach, and again if the user navigates to another device
       without a page reload.
    */
    @Override
    public void setParameter(BeforeEvent event, @OptionalParameter String deviceId) {
        this.deviceId = deviceId;
        this.renderedReceivedAt = null;
        cards.clear();
        heading.setText(deviceId != null ? deviceId : "No device selected");
        refresh();
    }

    /** The device id in the title, so several tabs can be told apart. */
    @Override
    public String getPageTitle() {
        return deviceId != null ? deviceId + " · ScrewCloud" : "ScrewCloud";
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();
        ui.setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = ui.addPollListener(event -> refresh());

        /*
           The token is only needed by the per-sensor alert settings, which are
           behind a popover, so the cards do not wait for it.
        */
        ClientId.resolve(ui, cards::setClientId);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }

    /*
       The cards and their history queries only run when the packet has changed.
       Devices send every five minutes while the view polls every five seconds,
       so the vast majority of polls bring nothing new. The age text is still
       refreshed every time, because it changes every second.
    */
    private void refresh() {
        if (deviceId == null) {
            emptyState.setText("Pick a device from the device list.");
            emptyState.setVisible(true);
            deviceStatus.setText("");
            return;
        }

        store.findLatest(deviceId).ifPresentOrElse(device -> {
            emptyState.setVisible(false);
            deviceStatus.setText("Updated %s · sequence %d"
                    .formatted(Ages.format(device.receivedAt()), device.sequence()));

            /*
               The same judgement the notifications use, so the page and the phone
               never say different things. Refreshed on every poll rather than only
               on a new packet: going offline is precisely the case where no packet
               arrives to trigger anything.
            */
            DeviceActivity activity = connections.activityOf(deviceId);
            offline.setVisible(activity.silent());
            if (activity.silent()) {
                offline.setText("Offline · nothing for %s, expected every %s".formatted(
                        Elapsed.approximate(activity.sinceLast()),
                        activity.expectedInterval().map(Elapsed::approximate).orElse("?")));
            }

            if (!device.receivedAt().equals(renderedReceivedAt)) {
                cards.show(device);
                renderedReceivedAt = device.receivedAt();
            }
        }, () -> {
            offline.setVisible(false);
            emptyState.setText("No measurements from %s yet.".formatted(deviceId));
            emptyState.setVisible(true);
            deviceStatus.setText("");
            cards.clear();
            renderedReceivedAt = null;
        });
    }
}
