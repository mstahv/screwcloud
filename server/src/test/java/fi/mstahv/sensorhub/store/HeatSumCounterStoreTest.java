package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;

@DatabaseTest
@Import({TestDatabase.class, HeatSumCounterStore.class})
class HeatSumCounterStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Autowired
    private HeatSumCounterStore store;

    @Test
    void aStartedCounterKeepsWhatItWasGiven() {
        store.start("LAHT", "DHT", "hirvi", 40, NOW);

        HeatSumCounter counter = store.countersFor("LAHT", "DHT").getFirst();

        assertEquals("hirvi", counter.getComment());
        assertEquals(40, counter.getTarget());
        assertEquals(NOW, counter.getStartedAt());
    }

    /*
       Both alerts default to on: a counter nobody is told about is a calendar
       reminder with extra steps.
    */
    @Test
    void bothAlertsAreOnByDefault() {
        store.start("LAHT", "DHT", "hirvi", HeatSumCounter.DEFAULT_TARGET, NOW);

        HeatSumCounter counter = store.countersFor("LAHT", "DHT").getFirst();

        assertTrue(counter.isAlertBeforeTarget());
        assertTrue(counter.isAlertAtTarget());
        assertFalse(counter.isNotifiedBeforeTarget());
        assertFalse(counter.isNotifiedAtTarget());
    }

    /*
       The reason this is a table and not more columns on sensor_settings: two
       carcasses hung on different days are two counters on one thermometer.
    */
    @Test
    void aSensorCanRunSeveralCounters() {
        store.start("LAHT", "DHT", "hirvi", 40, NOW.minus(Duration.ofDays(3)));
        store.start("LAHT", "DHT", "kauris", 30, NOW);

        List<HeatSumCounter> counters = store.countersFor("LAHT", "DHT");

        assertEquals(2, counters.size());
        // Oldest first, so the one that will finish first is at the top.
        assertEquals("hirvi", counters.getFirst().getComment());
    }

    @Test
    void countersAreScopedToTheSensor() {
        store.start("LAHT", "DHT", "hirvi", 40, NOW);

        assertTrue(store.countersFor("LAHT", "RBF").isEmpty());
        assertTrue(store.countersFor("TALO", "DHT").isEmpty());
        // But the whole device's counters are found together, for evaluating a packet.
        assertEquals(1, store.countersFor("LAHT").size());
    }

    @Test
    void anEmptyCommentIsStoredAsNothing() {
        store.start("LAHT", "DHT", "   ", 40, NOW);

        assertNull(store.countersFor("LAHT", "DHT").getFirst().getComment());
    }

    @Test
    void aCounterWithoutACommentIsDescribedBySensor() {
        store.start("LAHT", "DHT", null, 40, NOW);

        assertEquals("DHT", store.countersFor("LAHT", "DHT").getFirst().describe());
    }

    @Test
    void anImpossibleTargetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.start("LAHT", "DHT", "x", 0, NOW));
        assertThrows(IllegalArgumentException.class, () -> store.start("LAHT", "DHT", "x", -5, NOW));
        assertTrue(store.countersFor("LAHT", "DHT").isEmpty());
    }

    @Test
    void anOverlongCommentIsRejected() {
        String tooLong = "x".repeat(HeatSumCounter.MAX_COMMENT_LENGTH + 1);

        assertThrows(IllegalArgumentException.class,
                () -> store.start("LAHT", "DHT", tooLong, 40, NOW));
    }

    @Test
    void notificationsAreRecordedSoTheyHappenOnce() {
        long id = store.start("LAHT", "DHT", "hirvi", 40, NOW).getId();

        store.markNotified(id, true, false);

        assertTrue(store.find(id).orElseThrow().isNotifiedBeforeTarget());
        assertFalse(store.find(id).orElseThrow().isNotifiedAtTarget());
    }

    /*
       Raising the target means the reader wants the meat to hang longer, and they
       should hear about the new target rather than never again, having been told
       about the old one.
    */
    @Test
    void raisingTheTargetLetsTheNotificationsHappenAgain() {
        long id = store.start("LAHT", "DHT", "hirvi", 40, NOW).getId();
        store.markNotified(id, true, true);

        store.update(id, "hirvi", 60, true, true);

        HeatSumCounter counter = store.find(id).orElseThrow();
        assertEquals(60, counter.getTarget());
        assertFalse(counter.isNotifiedBeforeTarget());
        assertFalse(counter.isNotifiedAtTarget());
    }

    /*
       Lowering it does not: the target has already been announced, and re-announcing
       a lower one the sum has now passed would be noise.
    */
    @Test
    void loweringTheTargetKeepsTheNotificationsSpent() {
        long id = store.start("LAHT", "DHT", "hirvi", 40, NOW).getId();
        store.markNotified(id, true, true);

        store.update(id, "hirvi", 30, true, true);

        assertTrue(store.find(id).orElseThrow().isNotifiedAtTarget());
    }

    @Test
    void alertChoicesCanBeChanged() {
        long id = store.start("LAHT", "DHT", "hirvi", 40, NOW).getId();

        store.update(id, "hirvi ii", 40, false, true);

        HeatSumCounter counter = store.find(id).orElseThrow();
        assertEquals("hirvi ii", counter.getComment());
        assertFalse(counter.isAlertBeforeTarget());
        assertTrue(counter.isAlertAtTarget());
    }

    @Test
    void aStoppedCounterIsGone() {
        long id = store.start("LAHT", "DHT", "hirvi", 40, NOW).getId();

        store.stop(id);

        assertTrue(store.countersFor("LAHT", "DHT").isEmpty());
        assertTrue(store.find(id).isEmpty());
    }
}
