package fi.mstahv.sensorhub.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.HistoryPoint;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.PushSubscription;
import fi.mstahv.sensorhub.store.SensorSettingsStore;
import fi.mstahv.sensorhub.store.SensorThresholds;
import fi.mstahv.sensorhub.store.TemperatureZone;

/**
 * Turns arriving measurements into notifications, when a reading has moved from
 * one of a sensor's configured bands into another.
 *
 * <p>A sensor with no bands never notifies: without limits there is no such thing
 * as an alert. Configuring bands is therefore additive, as it is for the gauge.
 *
 * <p>Only changes are announced. A sensor that stays too warm for a day sends one
 * notification, not 288 of them — the reader already knows.
 *
 * <p>Getting worse is announced at once; getting better has to last. A reading
 * sitting on a limit crosses it every few minutes, and each crossing used to be a
 * change: one morning in the field produced seven notifications in an hour from a
 * single sensor. So a calmer reading is announced only once the sensor has stayed
 * out of the band it was last announced in for {@link #SETTLE}. Nothing is lost by
 * hearing "back to normal" an hour late — anyone who was worried is looking at the
 * dashboard, where the reading has been right all along.
 */
@Service
public class TemperatureAlerts {

    private static final Logger log = LoggerFactory.getLogger(TemperatureAlerts.class);

    /**
     * How long a sensor has to stay out of the band it was last announced in before
     * the calmer reading is worth a notification.
     *
     * <p>An hour is long on purpose. This delays nothing but good news, and the
     * alternative — announcing every crossing — is what turned one sensor hovering
     * on a limit into a notification every five minutes.
     */
    static final Duration SETTLE = Duration.ofHours(1);

    /**
     * How many readings are replayed to work out what the reader was last told.
     *
     * <p>Nothing is stored about that, deliberately: the readings are in the
     * database, the rule is a function of them, and a cache would empty on restart
     * and start announcing things that had already been announced. Sixty readings is
     * five hours at the usual five-minute rhythm — enough to cover the settling
     * window several times over, and cheap enough at three sensors per packet.
     */
    private static final int REPLAYED_READINGS = 60;

    private final MeasurementStore measurements;
    private final SensorSettingsStore settings;
    private final AlertSubscriptionStore subscriptions;
    private final WebPushService webPush;

    TemperatureAlerts(MeasurementStore measurements, SensorSettingsStore settings,
                      AlertSubscriptionStore subscriptions, WebPushService webPush) {
        this.measurements = measurements;
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.webPush = webPush;
    }

    /**
     * Examines a stored packet and notifies whoever subscribed to the transitions
     * it caused.
     *
     * <p>Call this <em>after</em> storing, because the previous reading is read
     * back from the database — the alternative would be a per-sensor cache that
     * empties on restart and then reports the first reading after a deployment as
     * a change.
     */
    public void evaluate(DeviceMeasurement measurement) {
        if (!webPush.isEnabled()) {
            return;
        }
        for (SensorMeasurement sensor : measurement.sensors()) {
            try {
                evaluateSensor(measurement.deviceId(), sensor);
            } catch (RuntimeException e) {
                // One sensor's problem must not cost the others their notification.
                log.warn("Evaluating alerts for {}/{} failed: {}",
                        measurement.deviceId(), sensor.sensorId(), e.getMessage());
            }
        }
    }

    private void evaluateSensor(String deviceId, SensorMeasurement sensor) {
        SensorThresholds thresholds = settings.thresholdsFor(deviceId, sensor.sensorId());
        if (thresholds.zoneOf(sensor.temperature()).isEmpty()) {
            return;
        }

        Optional<TemperatureZone> announce =
                transitionToAnnounce(recentZones(deviceId, sensor.sensorId(), thresholds), SETTLE);
        if (announce.isEmpty()) {
            return;
        }

        TemperatureZone zone = announce.get();
        List<PushSubscription> recipients =
                subscriptions.recipientsFor(deviceId, sensor.sensorId(), zone);
        if (recipients.isEmpty()) {
            return;
        }

        String title = title(deviceId, sensor.sensorId());
        String body = "%s · %s °C".formatted(
                zone.label(), String.format(Locale.ROOT, "%.2f", sensor.temperature()));
        log.info("{}/{} moved to {}, notifying {} subscriber(s)",
                deviceId, sensor.sensorId(), zone, recipients.size());
        recipients.forEach(recipient -> webPush.send(recipient, title, body));
    }

    /**
     * One reading, as the rule sees it: when it arrived and which band it fell in.
     */
    record ZoneAt(Instant at, TemperatureZone zone) {
    }

    /**
     * Which transition, if any, the newest of these readings announces.
     *
     * <p>The rule replays the readings and keeps track of what the reader was last
     * told, because that is not the same as the previous reading's band — the whole
     * point is that some readings are deliberately not announced.
     *
     * <ul>
     * <li><b>Worse, or sideways, is announced at once.</b> Drifting from a cold
     * warning straight to a warm one crossed the whole OK band in between and is
     * worth hearing about, even though both ends are "a warning".
     * <li><b>Calmer has to last.</b> The measure is how long ago the sensor was last
     * seen in the band the reader was told about: an hour without it is calm.
     * Wandering between calmer bands does not restart that clock, returning to the
     * announced one does, and what is finally announced is the band the sensor is in
     * <em>now</em> — if it came down through two limits during that hour, the middle
     * one was never news.
     * <li><b>A first reading is not a transition.</b> Announcing an OK one would
     * fire for every sensor the first time it reports, which is noise — but a
     * freezer that is already too warm the first time it reports is exactly what an
     * alert subscriber wants to know.
     * </ul>
     *
     * <p>Package private and static so the rule can be read and tested on its own.
     *
     * @param readings the sensor's recent readings, oldest first, the newest last
     * @param settle how long a calmer reading has to hold
     */
    static Optional<TemperatureZone> transitionToAnnounce(List<ZoneAt> readings, Duration settle) {
        if (readings.isEmpty()) {
            return Optional.empty();
        }
        if (readings.size() == 1) {
            TemperatureZone only = readings.get(0).zone();
            return only.severity() == TemperatureZone.Severity.OK
                    ? Optional.empty()
                    : Optional.of(only);
        }

        TemperatureZone announced = readings.get(0).zone();
        Instant lastSeenInThatBand = readings.get(0).at();
        boolean announcedByTheNewest = false;

        for (ZoneAt reading : readings.subList(1, readings.size())) {
            TemperatureZone zone = reading.zone();
            announcedByTheNewest = false;

            if (zone == announced) {
                // Still, or again, where the reader was told it was.
                lastSeenInThatBand = reading.at();
            } else if (isCalmerThan(zone, announced)) {
                boolean settled = Duration.between(lastSeenInThatBand, reading.at())
                        .compareTo(settle) >= 0;
                if (settled) {
                    announced = zone;
                    lastSeenInThatBand = reading.at();
                    announcedByTheNewest = true;
                }
            } else {
                announced = zone;
                lastSeenInThatBand = reading.at();
                announcedByTheNewest = true;
            }
        }
        return announcedByTheNewest ? Optional.of(announced) : Optional.empty();
    }

    private static boolean isCalmerThan(TemperatureZone zone, TemperatureZone other) {
        return zone.severity().compareTo(other.severity()) < 0;
    }

    /*
       Newest first from the store, oldest first for the replay. Readings the sensor
       gave no temperature for are left out rather than treated as a band of their
       own: they say nothing about where the sensor was.
    */
    private List<ZoneAt> recentZones(String deviceId, String sensorId, SensorThresholds thresholds) {
        List<HistoryPoint> newestFirst =
                measurements.measurements(deviceId, sensorId, PageRequest.of(0, REPLAYED_READINGS));
        List<ZoneAt> oldestFirst = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            HistoryPoint point = newestFirst.get(i);
            thresholds.zoneOf(point.temperature())
                    .ifPresent(zone -> oldestFirst.add(new ZoneAt(point.at(), zone)));
        }
        return oldestFirst;
    }

    /** The sensor's name if it has one, with the device so two "DHT"s are distinct. */
    private String title(String deviceId, String sensorId) {
        String name = settings.nameFor(deviceId, sensorId);
        return "%s (%s)".formatted(name != null ? name : sensorId, deviceId);
    }
}
