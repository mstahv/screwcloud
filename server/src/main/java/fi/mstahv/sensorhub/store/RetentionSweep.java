package fi.mstahv.sensorhub.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Forgets what nobody has touched in a year: the nightly janitor that keeps
 * the database the size of its living content.
 *
 * <p>Three independent expiries, each with its own configurable age (see
 * {@code application.properties}, {@code sensorhub.retention.*}):
 *
 * <ul>
 * <li><b>Browsers.</b> A token not seen on the pages past its retention loses
 * its device list, its push subscriptions and its alert choices. Receiving
 * notifications is deliberately not activity — a phone that only receives is
 * exactly the case where nobody is looking any more.</li>
 * <li><b>Devices.</b> A device whose newest measurement is older than its
 * retention is removed with everything it owns: measurements, sensor and
 * device settings, degree-day counters, alert subscriptions, and its rows on
 * everyone's lists. A device that never reported at all expires by the age of
 * its newest listing instead, so a box somebody added yesterday and is still
 * soldering is safe.</li>
 * <li><b>Measurements.</b> The oldest samples go once they pass their
 * retention, in batches, so the night the sweep first runs on an old database
 * takes many short locks rather than one enormous one.</li>
 * </ul>
 *
 * <p>The order matters and is the reverse of what reads naturally: devices are
 * judged <em>before</em> old samples are deleted, because a device's staleness
 * is read from the very measurements the sample purge is about to remove —
 * judged after, a long-dead device would look like one that never reported and
 * hang around on the strength of a stale listing.
 *
 * <p>Deletion, not disabling: there are no accounts to reactivate, and every
 * row here is something the reader can recreate by doing again what created it
 * — visiting, adding a device, flipping a switch. The one real loss is old
 * measurements, and keeping those forever is the disk filling up; see the
 * sizing note in {@code application.properties}.
 *
 * <p>Each delete is its own transaction. A janitor does not need atomicity:
 * whatever a crash leaves behind is swept the next night.
 */
@Service
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /** Small enough for short locks, large enough that a year purges in minutes. */
    static final int DELETE_BATCH = 10_000;

    private final MeasurementSampleRepository samples;
    private final SensorSettingsRepository sensorSettings;
    private final DeviceSettingsRepository deviceSettings;
    private final HeatSumCounterRepository heatSums;
    private final ClientDeviceRepository clientDevices;
    private final AlertSubscriptionRepository alertSubscriptions;
    private final PushSubscriptionRepository pushSubscriptions;
    private final ClientActivityRepository clientActivity;
    private final Clock clock;

    private final Duration sampleRetention;
    private final Duration deviceRetention;
    private final Duration clientRetention;

    @Autowired
    RetentionSweep(MeasurementSampleRepository samples,
                   SensorSettingsRepository sensorSettings,
                   DeviceSettingsRepository deviceSettings,
                   HeatSumCounterRepository heatSums,
                   ClientDeviceRepository clientDevices,
                   AlertSubscriptionRepository alertSubscriptions,
                   PushSubscriptionRepository pushSubscriptions,
                   ClientActivityRepository clientActivity,
                   @Value("${sensorhub.retention.samples:365d}") Duration sampleRetention,
                   @Value("${sensorhub.retention.devices:365d}") Duration deviceRetention,
                   @Value("${sensorhub.retention.clients:365d}") Duration clientRetention) {
        this(samples, sensorSettings, deviceSettings, heatSums, clientDevices,
                alertSubscriptions, pushSubscriptions, clientActivity,
                sampleRetention, deviceRetention, clientRetention, Clock.systemUTC());
    }

    RetentionSweep(MeasurementSampleRepository samples,
                   SensorSettingsRepository sensorSettings,
                   DeviceSettingsRepository deviceSettings,
                   HeatSumCounterRepository heatSums,
                   ClientDeviceRepository clientDevices,
                   AlertSubscriptionRepository alertSubscriptions,
                   PushSubscriptionRepository pushSubscriptions,
                   ClientActivityRepository clientActivity,
                   Duration sampleRetention, Duration deviceRetention,
                   Duration clientRetention, Clock clock) {
        this.samples = samples;
        this.sensorSettings = sensorSettings;
        this.deviceSettings = deviceSettings;
        this.heatSums = heatSums;
        this.clientDevices = clientDevices;
        this.alertSubscriptions = alertSubscriptions;
        this.pushSubscriptions = pushSubscriptions;
        this.clientActivity = clientActivity;
        this.sampleRetention = sampleRetention;
        this.deviceRetention = deviceRetention;
        this.clientRetention = clientRetention;
        this.clock = clock;
    }

    /**
     * At night, when a measurement arriving mid-sweep costs the least — and the
     * cron is configuration, so an operator can move it or, with "-", turn the
     * sweeping off entirely.
     */
    @Scheduled(cron = "${sensorhub.retention.cron:0 12 4 * * *}")
    public void sweep() {
        Instant now = clock.instant();
        forgetStaleClients(now.minus(clientRetention));
        removeStaleDevices(now.minus(deviceRetention));
        purgeOldSamples(now.minus(sampleRetention));
    }

    private void forgetStaleClients(Instant cutoff) {
        for (ClientActivity stale : clientActivity.findByLastSeenBefore(cutoff)) {
            String clientId = stale.getClientId();
            clientDevices.deleteByClientId(clientId);
            alertSubscriptions.deleteByClientId(clientId);
            pushSubscriptions.deleteByClientId(clientId);
            clientActivity.deleteByClientId(clientId);
            log.info("Retention: forgot client last seen {}", stale.getLastSeen());
        }
    }

    private void removeStaleDevices(Instant cutoff) {
        Set<String> stale = new TreeSet<>(samples.deviceIdsSilentSince(cutoff));

        /*
           Devices that exist only as configuration or a listing — never a
           packet. They have no measurement to be judged by, so the newest
           listing stands in: recently added means somebody is still waiting
           for the hardware, and no listing at all means configuration nothing
           points to any more.
        */
        Set<String> reporting = new HashSet<>(samples.findDeviceIds());
        Set<String> known = new TreeSet<>();
        known.addAll(clientDevices.findListedDeviceIds());
        known.addAll(sensorSettings.findConfiguredDeviceIds());
        known.addAll(deviceSettings.findConfiguredDeviceIds());
        known.addAll(heatSums.findCountingDeviceIds());
        for (String deviceId : known) {
            if (reporting.contains(deviceId)) {
                continue;
            }
            Instant newestListing = clientDevices.latestAddedAt(deviceId);
            if (newestListing == null || newestListing.isBefore(cutoff)) {
                stale.add(deviceId);
            }
        }

        for (String deviceId : stale) {
            samples.deleteByDeviceId(deviceId);
            sensorSettings.deleteByDeviceId(deviceId);
            deviceSettings.deleteByDeviceId(deviceId);
            heatSums.deleteByDeviceId(deviceId);
            alertSubscriptions.deleteByDeviceId(deviceId);
            clientDevices.deleteByDeviceId(deviceId);
            log.info("Retention: removed device {} and everything it owned", deviceId);
        }
    }

    private void purgeOldSamples(Instant cutoff) {
        long total = 0;
        int deleted;
        do {
            deleted = samples.deleteOldestBefore(cutoff, DELETE_BATCH);
            total += deleted;
        } while (deleted == DELETE_BATCH);
        if (total > 0) {
            log.info("Retention: purged {} measurements older than {}", total, cutoff);
        }
    }
}
