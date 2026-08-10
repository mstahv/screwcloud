package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;

@DatabaseTest
@Import({TestDatabase.class, AlertSubscriptionStore.class})
class AlertSubscriptionStoreTest {

    private static final String ALICE = "client-alice";
    private static final String BOB = "client-bob";

    private static final AlertPreferences ALERTS_ONLY = new AlertPreferences(true, false, false);
    private static final AlertPreferences EVERYTHING = new AlertPreferences(true, true, true);

    @Autowired
    private AlertSubscriptionStore store;

    @Test
    void nothingIsSubscribedByDefault() {
        assertFalse(store.hasPushSubscription(ALICE));
        assertTrue(store.preferencesFor(ALICE, "LAHT", "DHT").isSilent());
    }

    @Test
    void preferencesRoundTripPerSensor() {
        store.setPreferences(ALICE, "LAHT", "DHT", ALERTS_ONLY);

        AlertPreferences stored = store.preferencesFor(ALICE, "LAHT", "DHT");

        assertTrue(stored.onAlert());
        assertFalse(stored.onWarning());
        assertFalse(stored.onRecovery());
        // Another sensor and another browser are untouched.
        assertTrue(store.preferencesFor(ALICE, "LAHT", "RBF").isSilent());
        assertTrue(store.preferencesFor(BOB, "LAHT", "DHT").isSilent());
    }

    /*
       A row that would never match anything is deleted rather than kept as an
       all-false row, so the lookup when a packet arrives does not have to filter
       them out.
    */
    @Test
    void subscribingToNothingRemovesTheRow() {
        store.setPreferences(ALICE, "LAHT", "DHT", EVERYTHING);

        store.setPreferences(ALICE, "LAHT", "DHT", AlertPreferences.NONE);

        assertTrue(store.preferencesFor(ALICE, "LAHT", "DHT").isSilent());
        assertTrue(store.recipientsFor("LAHT", "DHT", TemperatureZone.ALERT_LOW).isEmpty());
    }

    /*
       The endpoint is unique, and a push service may hand the same browser a new
       one. Keeping both would send every notification twice.
    */
    @Test
    void resubscribingReplacesTheEndpoint() {
        store.subscribeToPush(ALICE, "https://push.example/one", "key-1", "auth-1");
        store.subscribeToPush(ALICE, "https://push.example/two", "key-2", "auth-2");

        List<PushSubscription> subscriptions = store.pushSubscriptionsFor(ALICE);

        assertEquals(1, subscriptions.size());
        assertEquals("https://push.example/two", subscriptions.getFirst().getEndpoint());
    }

    /*
       The global switch: turning notifications off must stop everything without
       touching the per-sensor choices, so turning it back on restores them.
    */
    @Test
    void unsubscribingFromPushKeepsThePerSensorChoices() {
        store.setPreferences(ALICE, "LAHT", "DHT", EVERYTHING);
        store.subscribeToPush(ALICE, "https://push.example/alice", "key", "auth");

        store.unsubscribeFromPush(ALICE);

        assertFalse(store.hasPushSubscription(ALICE));
        assertTrue(store.recipientsFor("LAHT", "DHT", TemperatureZone.ALERT_LOW).isEmpty());
        assertTrue(store.preferencesFor(ALICE, "LAHT", "DHT").onAlert());
    }

    /*
       The lookup that decides who gets a notification: subscribed to this sensor,
       wants this severity, and reachable.
    */
    @Test
    void recipientsAreThoseWhoWantThisSeverityAndAreReachable() {
        store.subscribeToPush(ALICE, "https://push.example/alice", "key", "auth");
        store.subscribeToPush(BOB, "https://push.example/bob", "key", "auth");
        store.setPreferences(ALICE, "LAHT", "DHT", ALERTS_ONLY);
        store.setPreferences(BOB, "LAHT", "DHT", new AlertPreferences(false, false, true));

        // An alert: only Alice asked for those.
        assertEquals(List.of("https://push.example/alice"),
                endpoints(store.recipientsFor("LAHT", "DHT", TemperatureZone.ALERT_HIGH)));
        // Back to OK: only Bob.
        assertEquals(List.of("https://push.example/bob"),
                endpoints(store.recipientsFor("LAHT", "DHT", TemperatureZone.OK)));
        // A warning: neither.
        assertTrue(store.recipientsFor("LAHT", "DHT", TemperatureZone.WARNING_LOW).isEmpty());
    }

    @Test
    void aSubscriberWithoutPushIsNotARecipient() {
        store.setPreferences(ALICE, "LAHT", "DHT", EVERYTHING);

        // Chosen the alerts but never switched notifications on.
        assertTrue(store.recipientsFor("LAHT", "DHT", TemperatureZone.ALERT_LOW).isEmpty());
    }

    @Test
    void recipientsAreScopedToTheSensor() {
        store.subscribeToPush(ALICE, "https://push.example/alice", "key", "auth");
        store.setPreferences(ALICE, "LAHT", "DHT", EVERYTHING);

        assertTrue(store.recipientsFor("LAHT", "RBF", TemperatureZone.ALERT_LOW).isEmpty());
        assertTrue(store.recipientsFor("TALO", "DHT", TemperatureZone.ALERT_LOW).isEmpty());
    }

    /*
       Dead endpoints are forgotten when the push service rejects them, otherwise
       every notification would keep being sent to browsers that are long gone.
    */
    @Test
    void aRejectedEndpointIsForgotten() {
        store.subscribeToPush(ALICE, "https://push.example/alice", "key", "auth");

        store.forgetEndpoint("https://push.example/alice");

        assertFalse(store.hasPushSubscription(ALICE));
    }

    private static List<String> endpoints(List<PushSubscription> subscriptions) {
        return subscriptions.stream().map(PushSubscription::getEndpoint).toList();
    }
}
