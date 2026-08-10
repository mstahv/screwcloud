package fi.mstahv.sensorhub.ui;

import java.util.function.Supplier;

import fi.mstahv.sensorhub.alerts.WebPushService;
import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.HeatSumCounterStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.SensorSettingsStore;

/**
 * What a sensor card needs from the application, in one parameter.
 *
 * <p>The cards had grown a constructor of four collaborators and were about to
 * take three more. Bundling them keeps the interesting arguments — which device,
 * which sensor — visible at the call site instead of buried among plumbing.
 *
 * @param clientId the browser token, as a supplier rather than a value: it comes
 *        back from the browser asynchronously, and a card may well be built
 *        before it arrives. Everything that needs it does so on a user action,
 *        by which time it is there.
 */
record SensorCardContext(
        SensorSettingsStore settings,
        MeasurementStore measurements,
        AlertSubscriptionStore alerts,
        HeatSumCounterStore heatSums,
        WebPushService webPush,
        Supplier<String> clientId) {
}
