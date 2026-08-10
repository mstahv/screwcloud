package fi.mstahv.sensorhub.alerts;

import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.ClientDeviceStore;
import fi.mstahv.sensorhub.store.HeatSumCounter;
import fi.mstahv.sensorhub.store.HeatSumCounterStore;
import fi.mstahv.sensorhub.store.MeasurementStore;

/**
 * Notifies about degree-day counters approaching and reaching their target.
 *
 * <p>Driven by arriving packets rather than by a clock, because that is when the
 * sum changes. Unlike a silent device, a counter cannot cross its target while
 * nothing is happening — no measurements means no accumulation.
 *
 * <p>Both notifications are sent once. The "once" is a column on the counter, not
 * a field in memory: a counter runs for days or weeks, and a server restart should
 * not announce a target passed last Tuesday all over again.
 */
@Service
public class HeatSumAlerts {

    private static final Logger log = LoggerFactory.getLogger(HeatSumAlerts.class);

    /**
     * How close to the target counts as "nearly there". A day, because that is the
     * notice someone needs to arrange being there when the meat comes down.
     */
    static final Duration NEARLY_THERE = Duration.ofDays(1);

    private final MeasurementStore measurements;
    private final HeatSumCounterStore counters;
    private final ClientDeviceStore clientDevices;
    private final AlertSubscriptionStore subscriptions;
    private final WebPushService webPush;

    HeatSumAlerts(MeasurementStore measurements, HeatSumCounterStore counters,
                  ClientDeviceStore clientDevices, AlertSubscriptionStore subscriptions,
                  WebPushService webPush) {
        this.measurements = measurements;
        this.counters = counters;
        this.clientDevices = clientDevices;
        this.subscriptions = subscriptions;
        this.webPush = webPush;
    }

    /** Called after a device's packet has been stored. */
    public void evaluate(String deviceId) {
        if (!webPush.isEnabled()) {
            return;
        }
        for (HeatSumCounter counter : counters.countersFor(deviceId)) {
            try {
                evaluate(counter);
            } catch (RuntimeException e) {
                // One counter's problem must not cost the others their notification.
                log.warn("Evaluating the counter on {}/{} failed: {}",
                        counter.getDeviceId(), counter.getSensorId(), e.getMessage());
            }
        }
    }

    private void evaluate(HeatSumCounter counter) {
        boolean wantsSomething =
                (counter.isAlertAtTarget() && !counter.isNotifiedAtTarget())
                        || (counter.isAlertBeforeTarget() && !counter.isNotifiedBeforeTarget());
        if (!wantsSomething) {
            // Nothing left to say about this one; no point summing its history.
            return;
        }

        HeatSum sum = HeatSum.of(measurements.history(
                counter.getDeviceId(), counter.getSensorId(), counter.getStartedAt()));

        if (sum.reached(counter.getTarget())) {
            /*
               At the target, the earlier warning is pointless — and is marked as
               sent so that lowering the target later does not produce a "nearly
               there" after the reader has already been told it is done.
            */
            if (counter.isAlertAtTarget() && !counter.isNotifiedAtTarget()) {
                announceReached(counter, sum);
            }
            counters.markNotified(counter.getId(), true, true);
            return;
        }

        if (counter.isAlertBeforeTarget() && !counter.isNotifiedBeforeTarget()
                && nearlyThere(sum, counter.getTarget())) {
            announceNearlyThere(counter, sum);
            counters.markNotified(counter.getId(), true, false);
        }
    }

    /*
       Based on the forecast rather than on the sum: "a day before" is a question
       about time, and at 2 °C a day is two degree-days while at 8 °C it is eight.
       A fixed margin in degree-days would mean a day's notice only at one
       temperature.
    */
    private static boolean nearlyThere(HeatSum sum, double target) {
        return sum.remaining(target)
                .map(remaining -> remaining.compareTo(NEARLY_THERE) <= 0)
                .orElse(false);
    }

    private void announceNearlyThere(HeatSumCounter counter, HeatSum sum) {
        String remaining = sum.remaining(counter.getTarget())
                .map(Elapsed::approximate)
                .orElse("a moment");
        log.info("{}/{} counter '{}' is nearly at {} degree-days",
                counter.getDeviceId(), counter.getSensorId(), counter.describe(),
                format(counter.getTarget()));
        notify(counter,
                counter.describe() + " is nearly ready",
                "%s of %s degree-days, about %s left at the current temperature."
                        .formatted(format(sum.degreeDays()), format(counter.getTarget()), remaining));
    }

    private void announceReached(HeatSumCounter counter, HeatSum sum) {
        log.info("{}/{} counter '{}' reached {} degree-days",
                counter.getDeviceId(), counter.getSensorId(), counter.describe(),
                format(sum.degreeDays()));
        notify(counter,
                counter.describe() + " is ready",
                "%s degree-days reached, the target was %s."
                        .formatted(format(sum.degreeDays()), format(counter.getTarget())));
    }

    /*
       Everyone who has notifications switched on for the device this counter runs
       on. The counter itself is not per browser — what hangs in the shed is a fact,
       not a preference — so the audience is whoever is watching that device.
    */
    private void notify(HeatSumCounter counter, String title, String body) {
        clientDevices.clientsWith(counter.getDeviceId()).stream()
                .flatMap(clientId -> subscriptions.pushSubscriptionsFor(clientId).stream())
                .forEach(recipient -> webPush.send(recipient, title, body));
    }

    private static String format(double degreeDays) {
        return String.format(Locale.ROOT, "%.1f", degreeDays);
    }
}
