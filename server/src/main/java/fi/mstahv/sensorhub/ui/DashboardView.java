package fi.mstahv.sensorhub.ui;

import java.time.Instant;

import com.flowingcode.vaadin.addons.relativetime.RelativeTime;

import com.vaadin.flow.component.AttachEvent;
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
import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.HeatSumCounterStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.SensorSettingsStore;
import fi.mstahv.sensorhub.updates.DeviceUpdates;

/**
 * One device's latest readings. The device comes from a URL parameter, for
 * example {@code /device/LAHT}, which makes the view linkable and bookmarkable.
 *
 * <p>The view does not check whether the device belongs to the caller's list:
 * knowing the address is enough. That is a deliberate choice — the device list is
 * a convenience, not access control.
 *
 * <p>Updates are pushed. The view subscribes to {@link DeviceUpdates} for the
 * device it is showing, and is woken when a packet for it arrives — and once a
 * minute by the sweep that decides whether a device has gone quiet, which is the one
 * change no packet announces.
 */
@Route("device")
@StyleSheet(Aura.STYLESHEET)
// After Aura, because it sets the tokens Aura reads. See the file.
@StyleSheet("/styles/sunset-glass.css")
public class DashboardView extends VerticalLayout
        implements HasUrlParameter<String>, HasDynamicTitle {


    private final MeasurementStore store;
    private final ConnectionMonitor connections;
    private final DeviceUpdates updates;
    private final SensorCardLayout cards;
    private final H2 heading = new H2();
    private final SecondaryText deviceStatus = new SecondaryText();
    private final OfflineBadge offline = new OfflineBadge();
    private final Span emptyState = new Span();

    /** This view's interest in one device, dropped when it switches to another. */
    private Registration subscription;
    private String deviceId;

    /** The last packet rendered, so an unchanged state can be skipped. */
    private Instant renderedReceivedAt;

    public DashboardView(MeasurementStore store, SensorSettingsStore settings,
                         AlertSubscriptionStore alerts, HeatSumCounterStore heatSums,
                         WebPushService webPush, ConnectionMonitor connections,
                         DeviceUpdates updates) {
        this.store = store;
        this.connections = connections;
        this.updates = updates;
        this.cards = new SensorCardLayout(store, settings, alerts, heatSums, webPush);
        /*
           Width full and a floor for the height, rather than setSizeFull(). A view
           pinned to the viewport's height ends there and takes its bottom padding
           with it: with three sensors the cards carried on past the bottom of that
           box, so the last one finished flush against the end of the document while
           the page's own margin sat a screenful above it. On a phone that reads as a
           page that has been cut off rather than one that has ended. A minimum keeps
           what full height was for — a short page still fills the screen — and lets
           the box grow when there is more than a screenful to show.
        */
        setWidthFull();
        setMinHeight("100%");

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
        subscribe();
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
        /*
           setParameter runs before the first attach, so the subscription it asked
           for could not be made then — there was no UI to push through yet. This is
           the first chance, and the reason subscribe() is idempotent.
        */
        subscribe();

        /*
           The token is only needed by the per-sensor alert settings, which are
           behind a popover, so the cards do not wait for it.
        */
        ClientId.resolve(attachEvent.getUI(), cards::setClientId);
    }

    /*
       There is no onDetach here any more. DeviceUpdates drops a subscription when
       its view detaches, which is one fewer thing that can be forgotten; what is
       left is the case it cannot see — this view staying on screen and changing
       which device it is about.
    */
    private void subscribe() {
        if (subscription != null) {
            subscription.remove();
            subscription = null;
        }
        if (deviceId == null || getUI().isEmpty()) {
            return;
        }
        subscription = updates.forDevice(this, deviceId, this::refresh);
    }

    /*
       Runs when a packet for this device arrives, and once a minute when the sweep
       that judges silence has run. The cards and their history queries still go
       through the "has the packet changed" gate, because the sweep brings nothing
       new for a device that is reporting normally.
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
            showStatus(device);

            /*
               Re-read on every wake-up rather than only when a packet arrives: going
               offline is precisely the case where no packet arrives to trigger
               anything, which is what the minute sweep is for.
            */
            offline.show(connections.activityOf(deviceId));

            if (!device.receivedAt().equals(renderedReceivedAt)) {
                cards.show(device);
                renderedReceivedAt = device.receivedAt();
            }
        }, () -> {
            offline.hide();
            emptyState.setText("No measurements from %s yet.".formatted(deviceId));
            emptyState.setVisible(true);
            deviceStatus.removeAll();
            cards.clear();
            renderedReceivedAt = null;
        });
    }

    /**
     * When the packet arrived, which one it was, and how warm the board itself is.
     *
     * <p>The chip temperature is here rather than on a card of its own because it
     * is about the device, like the rest of this line — it says how hard the box is
     * working, not what the weather is doing. A card gave it the same standing as a
     * measuring point somebody placed deliberately, with a heading, a sparkline and
     * alert settings, none of which anyone wants for it.
     */
    private void showStatus(DeviceMeasurement device) {
        deviceStatus.removeAll();
        /*
           The time is a component so that it stays true between packets: a device
           that reports hourly leaves this line on screen for an hour, and a written
           "Updated 2 min ago" would spend fifty-eight of those minutes lying.
        */
        deviceStatus.add(new Span("Updated "), new RelativeTime(device.receivedAt()),
                new Span(statusOf(device)));
    }

    /**
     * Whether the device is still reporting, shown only when it is not: a green
     * "online" badge on every visit would be noise, since the interesting state is
     * the exceptional one.
     *
     * <p>The judgement comes in as a {@link DeviceActivity} — the same one the push
     * notifications act on, so the page and the phone can never say different
     * things about the same device.
     */
    private static class OfflineBadge extends Badge {

        OfflineBadge() {
            addThemeVariants(BadgeVariant.ERROR);
            setVisible(false);
        }

        void show(DeviceActivity activity) {
            setVisible(activity.silent());
            if (activity.silent()) {
                setText("Offline · nothing for %s, expected every %s".formatted(
                        Elapsed.approximate(activity.sinceLast()),
                        activity.expectedInterval().map(Elapsed::approximate).orElse("?")));
            }
        }

        void hide() {
            setVisible(false);
        }
    }

    private static String statusOf(DeviceMeasurement device) {
        String status = " · sequence %d".formatted(device.sequence());
        /*
           Not when the chip is the only thing this device measures: it has a card
           of its own then, and the same number in two places on one screen reads
           as two readings that happen to agree.
        */
        if (device.measuresOnlyItself()) {
            return status;
        }
        return device.sensors().stream()
                .filter(SensorMeasurement::isDeviceInternal)
                .map(SensorMeasurement::temperature)
                .filter(temperature -> temperature != null)
                .findFirst()
                .map(temperature -> status + " · CPU " + Readings.format(temperature, "%.1f °C"))
                .orElse(status);
    }
}
