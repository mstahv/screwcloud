package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.DeviceId;
import fi.mstahv.sensorhub.validation.PushEndpoint;
import fi.mstahv.sensorhub.validation.SensorId;

/**
 * Who has subscribed to what: the browsers' push endpoints and their per-sensor
 * alert choices.
 *
 * <p>Both live here because they are always used together — deciding whether to
 * send a notification means asking "who wants this sensor's alerts" and "where do
 * I reach them".
 *
 * <p>The push subscription comes from the browser, which makes it the one thing
 * here that arrives from outside. It is checked on the way in rather than only in
 * the entity, so a bad endpoint is refused before anything is deleted to make room
 * for it.
 */
@Service
@Validated
public class AlertSubscriptionStore {

    private final PushSubscriptionRepository pushSubscriptions;
    private final AlertSubscriptionRepository alertSubscriptions;

    AlertSubscriptionStore(PushSubscriptionRepository pushSubscriptions,
                           AlertSubscriptionRepository alertSubscriptions) {
        this.pushSubscriptions = pushSubscriptions;
        this.alertSubscriptions = alertSubscriptions;
    }

    /**
     * Stores where a browser can be reached, replacing any earlier endpoint of
     * the same browser.
     *
     * <p>A push service may hand the same browser a new endpoint, and the browser
     * itself may be re-subscribed after being cleared. Keeping only the newest
     * avoids sending every notification twice to a browser that has been through
     * that.
     */
    @Transactional
    public void subscribeToPush(@NotBlank @Size(max = 64) String clientId,
                                @NotBlank @PushEndpoint String endpoint,
                                @NotBlank String p256dh, @NotBlank String auth) {
        /*
           Deleting by endpoint as well as by client: the same endpoint could in
           principle already belong to another client token, for instance after
           localStorage was cleared but the push subscription was not, and the
           endpoint is unique.
        */
        pushSubscriptions.deleteByClientId(clientId);
        pushSubscriptions.deleteByEndpoint(endpoint);
        pushSubscriptions.flush();
        pushSubscriptions.save(
                new PushSubscription(clientId, endpoint, p256dh, auth, Instant.now()));
    }

    /** Stops all notifications to a browser, whatever its per-sensor choices say. */
    @Transactional
    public void unsubscribeFromPush(String clientId) {
        pushSubscriptions.deleteByClientId(clientId);
    }

    /**
     * Removes an endpoint the push service has rejected as gone. Called when a
     * send fails permanently, so dead subscriptions do not accumulate.
     */
    @Transactional
    public void forgetEndpoint(String endpoint) {
        pushSubscriptions.deleteByEndpoint(endpoint);
    }

    @Transactional(readOnly = true)
    public boolean hasPushSubscription(String clientId) {
        return !pushSubscriptions.findByClientId(clientId).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<PushSubscription> pushSubscriptionsFor(String clientId) {
        return pushSubscriptions.findByClientId(clientId);
    }

    @Transactional(readOnly = true)
    public AlertPreferences preferencesFor(String clientId, String deviceId, String sensorId) {
        return alertSubscriptions.findByClientIdAndDeviceIdAndSensorId(clientId, deviceId, sensorId)
                .map(subscription -> new AlertPreferences(
                        subscription.isOnAlert(),
                        subscription.isOnWarning(),
                        subscription.isOnRecovery()))
                .orElse(AlertPreferences.NONE);
    }

    /**
     * Stores what a browser wants from one sensor. Subscribing to nothing deletes
     * the row rather than keeping one that would never match anything.
     */
    @Transactional
    public void setPreferences(@NotBlank @Size(max = 64) String clientId,
                               @NotBlank @DeviceId String deviceId,
                               @NotNull @SensorId String sensorId,
                               @NotNull AlertPreferences preferences) {
        AlertSubscription subscription = alertSubscriptions
                .findByClientIdAndDeviceIdAndSensorId(clientId, deviceId, sensorId)
                .orElseGet(() -> new AlertSubscription(clientId, deviceId, sensorId));

        subscription.setOnAlert(preferences.onAlert());
        subscription.setOnWarning(preferences.onWarning());
        subscription.setOnRecovery(preferences.onRecovery());

        if (subscription.isSilent()) {
            alertSubscriptions.findByClientIdAndDeviceIdAndSensorId(clientId, deviceId, sensorId)
                    .ifPresent(alertSubscriptions::delete);
            return;
        }
        alertSubscriptions.save(subscription);
    }

    /**
     * Everyone who wants to hear about a transition into the given zone on this
     * sensor, as the push endpoints to deliver to.
     *
     * <p>Browsers that have turned notifications off have no push subscription and
     * therefore drop out here, without their per-sensor choices being touched.
     */
    @Transactional(readOnly = true)
    public List<PushSubscription> recipientsFor(String deviceId, String sensorId,
                                                TemperatureZone zone) {
        return alertSubscriptions.findByDeviceIdAndSensorId(deviceId, sensorId).stream()
                .filter(subscription -> new AlertPreferences(
                        subscription.isOnAlert(),
                        subscription.isOnWarning(),
                        subscription.isOnRecovery()).wants(zone))
                .map(AlertSubscription::getClientId)
                .distinct()
                .flatMap(clientId -> pushSubscriptions.findByClientId(clientId).stream())
                .toList();
    }
}
