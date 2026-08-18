package fi.mstahv.sensorhub.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.ClientDeviceStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.updates.DeviceUpdates;

/**
 * Watches for devices that have stopped reporting.
 *
 * <p>This is the failure the rest of the system cannot see. Every other alert is
 * triggered by an arriving packet; a device whose power is out or whose WiFi has
 * dropped sends nothing at all, and the last reading it managed sits in the UI
 * looking perfectly fine. So this one has to be driven by a clock instead.
 *
 * <p>It answers two questions with the same rule, so a badge and a notification
 * can never disagree: {@link #activityOf} for the views, and a scheduled sweep
 * for the notifications.
 */
@Service
public class ConnectionMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConnectionMonitor.class);

    /**
     * How many recent arrivals the rhythm is learned from. Twelve is an hour of a
     * five-minute device: long enough for a stable median, short enough to follow
     * a device whose interval was changed.
     */
    private static final int ARRIVALS_CONSIDERED = 12;

    /**
     * How often the sweep runs. Once a minute is far finer than any device's
     * interval, so the delay it adds to noticing an outage is negligible, and it
     * is one cheap query per watched device.
     */
    private static final long SWEEP_INTERVAL_MS = 60_000;

    private final MeasurementStore measurements;
    private final ClientDeviceStore clientDevices;
    private final AlertSubscriptionStore subscriptions;
    private final WebPushService webPush;
    private final DeviceUpdates updates;
    private final Clock clock;

    /**
     * Devices currently reported as silent, mapped to the last packet that did
     * arrive — not to when the sweep noticed. Recovery can then report the real
     * length of the outage instead of one that is short by the whole detection
     * threshold.
     *
     * <p>In memory, not in the database. A restart therefore forgets an ongoing
     * outage and notifies about it once more, which is a reasonable thing to be told
     * after a server restart — and much less machinery than a table whose only
     * purpose is to suppress it.
     */
    private final Map<String, Instant> silentSince = new ConcurrentHashMap<>();

    /*
       @Autowired even though it is the only public-facing constructor: there are
       two, and without the marker Spring looks for a no-argument one and fails to
       start.
    */
    @Autowired
    ConnectionMonitor(MeasurementStore measurements, ClientDeviceStore clientDevices,
                      AlertSubscriptionStore subscriptions, WebPushService webPush,
                      DeviceUpdates updates) {
        this(measurements, clientDevices, subscriptions, webPush, updates, Clock.systemUTC());
    }

    /** Visible for tests, which need to decide what "now" is. */
    ConnectionMonitor(MeasurementStore measurements, ClientDeviceStore clientDevices,
                      AlertSubscriptionStore subscriptions, WebPushService webPush,
                      DeviceUpdates updates, Clock clock) {
        this.measurements = measurements;
        this.clientDevices = clientDevices;
        this.subscriptions = subscriptions;
        this.webPush = webPush;
        this.updates = updates;
        this.clock = clock;
    }

    /**
     * Whether a device is still reporting, for the UI to show.
     *
     * <p>Independent of any subscription: the badge is worth showing whether or not
     * anyone asked to be notified.
     */
    public DeviceActivity activityOf(String deviceId) {
        if (deviceId == null) {
            return DeviceActivity.neverHeard();
        }
        return DeviceActivity.of(
                measurements.recentArrivals(deviceId, ARRIVALS_CONSIDERED), clock.instant());
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = SWEEP_INTERVAL_MS)
    void sweep() {
        try {
            /*
               Only the notifications need this part, and only when they can be sent
               at all; the pages are told either way, below.
            */
            if (webPush.isEnabled()) {
                clientDevices.clientsWatchingForSilence().forEach(this::check);
            }
        } catch (RuntimeException e) {
            // A scheduled task that throws is never run again. Not worth that.
            log.warn("The connection sweep failed: {}", e.getMessage());
        }

        /*
           A device going quiet is the one change no packet announces, so an open page
           would never hear about it: everything else on these pages arrives as an
           arrival. This is where the badge comes from, and it is a single timer on
           the server rather than one poll per open browser.
        */
        updates.swept();
    }

    private void check(String deviceId, List<String> watchers) {
        DeviceActivity activity = activityOf(deviceId);
        Instant since = silentSince.get(deviceId);

        if (activity.silent() && since == null) {
            silentSince.put(deviceId, clock.instant().minus(activity.sinceLast()));
            announceSilence(deviceId, activity, watchers);
        } else if (!activity.silent() && since != null) {
            silentSince.remove(deviceId);
            /*
               Only announced if the device had actually been reported silent, so
               nobody gets a "back online" for an outage they were never told
               about.
            */
            announceRecovery(deviceId, since, watchers);
        }
    }

    private void announceSilence(String deviceId, DeviceActivity activity, List<String> watchers) {
        String expected = activity.expectedInterval()
                .map(interval -> ", expected every " + Elapsed.approximate(interval))
                .orElse("");
        log.info("{} has gone silent: nothing for {}{}",
                deviceId, Elapsed.approximate(activity.sinceLast()), expected);
        notify(watchers,
                deviceId + " is not reporting",
                "Nothing for %s%s. Check the power and the network."
                        .formatted(Elapsed.approximate(activity.sinceLast()), expected));
    }

    private void announceRecovery(String deviceId, Instant lastSeenBefore, List<String> watchers) {
        Duration outage = Duration.between(lastSeenBefore, clock.instant());
        log.info("{} is reporting again after {}", deviceId, Elapsed.approximate(outage));
        notify(watchers,
                deviceId + " is reporting again",
                "Back after about " + Elapsed.approximate(outage) + ".");
    }

    private void notify(List<String> clientIds, String title, String body) {
        clientIds.stream()
                .flatMap(clientId -> subscriptions.pushSubscriptionsFor(clientId).stream())
                .forEach(subscription -> webPush.send(subscription, title, body));
    }
}
