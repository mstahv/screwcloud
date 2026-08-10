package fi.mstahv.sensorhub.alerts;

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
 */
@Service
public class TemperatureAlerts {

    private static final Logger log = LoggerFactory.getLogger(TemperatureAlerts.class);

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
        Optional<TemperatureZone> current = thresholds.zoneOf(sensor.temperature());
        if (current.isEmpty()) {
            return;
        }

        Optional<TemperatureZone> previous = previousZone(deviceId, sensor.sensorId(), thresholds);
        Optional<TemperatureZone> announce = transitionToAnnounce(previous, current.get());
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
     * Which transitions are worth a notification.
     *
     * <p>A change of band, not of severity: drifting from a cold warning straight
     * to a warm one crossed the whole OK band in between and is worth hearing
     * about, even though both ends are "a warning".
     *
     * <p>A sensor with no previous reading is announced only when it is not OK.
     * There is nothing to compare against, so this is the one case that is not a
     * transition at all — but a freezer that is already too warm the first time it
     * reports is exactly what someone subscribed to alerts wants to know.
     *
     * <p>Package private and static so the rule can be read and tested on its own.
     */
    static Optional<TemperatureZone> transitionToAnnounce(
            Optional<TemperatureZone> previous, TemperatureZone current) {
        if (previous.isEmpty()) {
            return current.severity() == TemperatureZone.Severity.OK
                    ? Optional.empty()
                    : Optional.of(current);
        }
        return previous.get() == current ? Optional.empty() : Optional.of(current);
    }

    /*
       The two newest rows are the one just stored and the one before it. Asking
       for two and skipping the first is cheaper than a query dedicated to
       "second newest", and it reuses the ordering the grid already relies on.
    */
    private Optional<TemperatureZone> previousZone(String deviceId, String sensorId,
                                                   SensorThresholds thresholds) {
        List<HistoryPoint> newest =
                measurements.measurements(deviceId, sensorId, PageRequest.of(0, 2));
        if (newest.size() < 2) {
            return Optional.empty();
        }
        return thresholds.zoneOf(newest.get(1).temperature());
    }

    /** The sensor's name if it has one, with the device so two "DHT"s are distinct. */
    private String title(String deviceId, String sensorId) {
        String name = settings.nameFor(deviceId, sensorId);
        return "%s (%s)".formatted(name != null ? name : sensorId, deviceId);
    }
}
