package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;
import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;
import fi.mstahv.sensorhub.alerts.WebPushService;
import fi.mstahv.sensorhub.store.AlertSubscriptionStore;
import fi.mstahv.sensorhub.store.HeatSumCounterStore;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.store.SensorSettingsStore;

/**
 * Component reuse is the point here: the view used to remove every card and
 * create new ones on each poll, which forces Vaadin to resend the whole DOM
 * structure every five seconds.
 */
@DatabaseTest
@Import({TestDatabase.class, MeasurementStore.class, SensorSettingsStore.class,
        AlertSubscriptionStore.class, HeatSumCounterStore.class})
class SensorCardLayoutTest {

    /*
       Notifications are a separate concern from card reuse, and a real
       WebPushService would need VAPID keys. Mocked, it reports itself disabled,
       which is also what a deployment without keys looks like.
    */
    @MockitoBean
    private WebPushService webPush;

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Autowired
    private MeasurementStore store;

    @Autowired
    private SensorSettingsStore settings;

    @Autowired
    private AlertSubscriptionStore alerts;

    @Autowired
    private HeatSumCounterStore heatSums;

    private SensorCardLayout layout;

    @BeforeEach
    void setUp() {
        layout = new SensorCardLayout(store, settings, alerts, heatSums, webPush);
    }

    @Test
    void reusesSameCardInstancesWhenSensorsAreUnchanged() {
        DeviceMeasurement first = packet("LAHT", 1, NOW, 20.0);
        layout.show(first);
        SensorCard dhtCard = layout.cardFor("DHT");
        SensorCard ruuviCard = layout.cardFor("RBF");

        layout.show(packet("LAHT", 2, NOW.plusSeconds(300), 21.0));

        assertSame(dhtCard, layout.cardFor("DHT"));
        assertSame(ruuviCard, layout.cardFor("RBF"));
        assertEquals(2, layout.cardCount());
    }

    @Test
    void addsCardForNewSensorWithoutTouchingExistingOnes() {
        layout.show(packet("LAHT", 1, NOW, 20.0));
        SensorCard dhtCard = layout.cardFor("DHT");

        layout.show(new DeviceMeasurement("LAHT", 2, NOW.plusSeconds(300), List.of(
                new SensorMeasurement("DHT", 21.0, 40.0),
                new SensorMeasurement("RBF", 22.0, 45.0),
                new SensorMeasurement("ULK", -3.0, 88.0))));

        assertSame(dhtCard, layout.cardFor("DHT"));
        assertEquals(3, layout.cardCount());
    }

    @Test
    void removesCardWhenSensorDisappears() {
        layout.show(packet("LAHT", 1, NOW, 20.0));

        layout.show(new DeviceMeasurement("LAHT", 2, NOW.plusSeconds(300),
                List.of(new SensorMeasurement("DHT", 21.0, 40.0))));

        assertEquals(1, layout.cardCount());
        assertEquals(null, layout.cardFor("RBF"));
    }

    /*
       A card remembers the device id for its settings, and sensor ids are only
       unique within a device. Two devices' "DHT" must therefore not share a card.
    */
    @Test
    void createsFreshCardsWhenDeviceChanges() {
        layout.show(packet("LAHT", 1, NOW, 20.0));
        SensorCard lahtiCard = layout.cardFor("DHT");

        layout.show(packet("TALO", 1, NOW, 21.0));

        assertNotSame(lahtiCard, layout.cardFor("DHT"));
        assertEquals(2, layout.cardCount());
    }

    @Test
    void cardTitleFollowsSensorName() {
        layout.show(packet("LAHT", 1, NOW, 20.0));
        assertEquals("DHT", layout.cardFor("DHT").getTitleAsText());

        settings.rename("LAHT", "DHT", "Cold room");
        // A freshly created card reads the name at construction.
        layout.clear();
        layout.show(packet("LAHT", 2, NOW.plusSeconds(300), 21.0));

        assertEquals("Cold room", layout.cardFor("DHT").getTitleAsText());
    }

    private static DeviceMeasurement packet(String deviceId, int sequence, Instant at, double temperature) {
        return new DeviceMeasurement(deviceId, sequence, at, List.of(
                new SensorMeasurement("DHT", temperature, 40.0),
                new SensorMeasurement("RBF", temperature + 1, 45.0)));
    }
}
