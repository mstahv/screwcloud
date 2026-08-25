package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;

/**
 * The nightly janitor's three promises: old measurements go, dead devices go
 * with everything they own, and browsers that stopped visiting are forgotten —
 * while everything alive is left exactly as it was.
 */
@DatabaseTest
@Import({TestDatabase.class, DeviceSettingsStore.class, SensorSettingsStore.class})
class RetentionSweepTest {

    private static final Instant NOW = Instant.parse("2026-08-25T04:12:00Z");
    private static final Duration YEAR = Duration.ofDays(365);

    @Autowired
    private MeasurementSampleRepository samples;
    @Autowired
    private SensorSettingsRepository sensorSettings;
    @Autowired
    private DeviceSettingsRepository deviceSettings;
    @Autowired
    private HeatSumCounterRepository heatSums;
    @Autowired
    private ClientDeviceRepository clientDevices;
    @Autowired
    private AlertSubscriptionRepository alertSubscriptions;
    @Autowired
    private PushSubscriptionRepository pushSubscriptions;
    @Autowired
    private ClientActivityRepository clientActivity;

    private RetentionSweep sweep;

    @BeforeEach
    void buildSweep() {
        sweep = new RetentionSweep(samples, sensorSettings, deviceSettings, heatSums,
                clientDevices, alertSubscriptions, pushSubscriptions, clientActivity,
                YEAR, YEAR, YEAR, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void oldSamplesGoAndFreshOnesStay() {
        sample("LAHT", NOW.minus(Duration.ofDays(400)), 1);
        sample("LAHT", NOW.minus(Duration.ofDays(1)), 2);

        sweep.sweep();

        List<Instant> left = samples.findAll().stream()
                .map(MeasurementSample::getReceivedAt).toList();
        assertEquals(List.of(NOW.minus(Duration.ofDays(1))), left,
                "A year is kept, older is purged");
    }

    /* More rows than one delete batch, so the batching loop is what is tested. */
    @Test
    void aBacklogLargerThanOneBatchIsPurgedCompletely() {
        for (int i = 0; i < RetentionSweep.DELETE_BATCH + 50; i++) {
            sample("BULK", NOW.minus(Duration.ofDays(400)).plusSeconds(i), i);
        }

        sweep.sweep();

        assertEquals(0, samples.count(), "the loop must not stop after one batch");
    }

    @Test
    void aSilentDeviceIsRemovedWithEverythingItOwns() {
        sample("DEAD", NOW.minus(Duration.ofDays(400)), 1);
        belongings("DEAD", "alice");
        sample("LIVE", NOW.minus(Duration.ofDays(2)), 1);
        belongings("LIVE", "alice");
        clientActivity.save(new ClientActivity("alice", NOW));

        sweep.sweep();

        assertTrue(samples.findByDeviceIdAndSensorId("DEAD", "DHT",
                org.springframework.data.domain.Pageable.ofSize(1)).isEmpty());
        assertTrue(sensorSettings.findByDeviceIdAndSensorId("DEAD", "DHT").isEmpty());
        assertTrue(deviceSettings.findByDeviceId("DEAD").isEmpty());
        assertTrue(heatSums.findByDeviceId("DEAD").isEmpty());
        assertTrue(clientDevices.findByDeviceId("DEAD").isEmpty());
        assertTrue(alertSubscriptions.findByDeviceIdAndSensorId("DEAD", "DHT").isEmpty());

        // The living device keeps every one of the same things.
        assertFalse(sensorSettings.findByDeviceIdAndSensorId("LIVE", "DHT").isEmpty());
        assertFalse(deviceSettings.findByDeviceId("LIVE").isEmpty());
        assertFalse(heatSums.findByDeviceId("LIVE").isEmpty());
        assertFalse(clientDevices.findByDeviceId("LIVE").isEmpty());
        assertFalse(alertSubscriptions.findByDeviceIdAndSensorId("LIVE", "DHT").isEmpty());
    }

    /*
       A device that never reported has no measurement to be judged by, so its
       newest listing stands in — and a fresh listing is somebody still waiting
       for hardware, which must not be tidied away.
    */
    @Test
    void aDeviceThatNeverReportedExpiresByItsListingAge() {
        listed("OLD1", "alice", NOW.minus(Duration.ofDays(400)));
        listed("NEW1", "alice", NOW.minus(Duration.ofDays(3)));
        clientActivity.save(new ClientActivity("alice", NOW));

        sweep.sweep();

        assertTrue(clientDevices.findByDeviceId("OLD1").isEmpty(),
                "A listing nobody has refreshed in a year is a device that never came");
        assertFalse(clientDevices.findByDeviceId("NEW1").isEmpty(),
                "A fresh listing is somebody still soldering");
    }

    @Test
    void aBrowserNotSeenInAYearIsForgotten() {
        clientActivity.save(new ClientActivity("gone", NOW.minus(Duration.ofDays(400))));
        clientActivity.save(new ClientActivity("here", NOW.minus(Duration.ofDays(4))));
        for (String client : List.of("gone", "here")) {
            // The device itself stays alive, so only the browser's rows are judged.
            sample("LAHT", NOW.minus(Duration.ofDays(1)), 1);
            listed("LAHT", client, NOW.minus(Duration.ofDays(1)));
            alertSubscriptions.save(subscription(client, "LAHT"));
            pushSubscriptions.save(push(client));
        }

        sweep.sweep();

        assertTrue(clientDevices.findByClientIdOrderByDeviceIdAsc("gone").isEmpty());
        assertTrue(pushSubscriptions.findByClientId("gone").isEmpty());
        assertNull(alertSubscriptions
                .findByClientIdAndDeviceIdAndSensorId("gone", "LAHT", "DHT").orElse(null));
        assertTrue(clientActivity.findById("gone").isEmpty());

        assertFalse(clientDevices.findByClientIdOrderByDeviceIdAsc("here").isEmpty());
        assertFalse(pushSubscriptions.findByClientId("here").isEmpty());
        assertTrue(clientActivity.findById("here").isPresent());
    }

    private void sample(String deviceId, Instant at, int sequence) {
        samples.save(new MeasurementSample(deviceId, "DHT", 6.2, 71.0, at, sequence));
    }

    /** One of everything a device can own. */
    private void belongings(String deviceId, String clientId) {
        SensorSettings sensor = new SensorSettings(deviceId, "DHT");
        sensor.setName("Named");
        sensorSettings.save(sensor);

        DeviceSettings device = new DeviceSettings(deviceId);
        device.setName("Named device");
        deviceSettings.save(device);

        heatSums.save(new HeatSumCounter(deviceId, "DHT", "hirvi", 40.0,
                NOW.minus(Duration.ofDays(2))));

        listed(deviceId, clientId, NOW.minus(Duration.ofDays(2)));
        alertSubscriptions.save(subscription(clientId, deviceId));
    }

    private void listed(String deviceId, String clientId, Instant addedAt) {
        if (clientDevices.existsByClientIdAndDeviceId(clientId, deviceId)) {
            return;
        }
        clientDevices.save(new ClientDevice(clientId, deviceId, addedAt));
    }

    private AlertSubscription subscription(String clientId, String deviceId) {
        AlertSubscription row = new AlertSubscription(clientId, deviceId, "DHT");
        row.setOnAlert(true);
        return row;
    }

    private PushSubscription push(String clientId) {
        return new PushSubscription(clientId,
                "https://push.example.com/" + clientId,
                "p256dh-" + clientId, "auth-" + clientId, NOW);
    }
}
