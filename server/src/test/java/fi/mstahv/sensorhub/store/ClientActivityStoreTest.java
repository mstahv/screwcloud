package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;

@DatabaseTest
@Import(TestDatabase.class)
class ClientActivityStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

    @Autowired
    private ClientActivityRepository repository;

    @Test
    void aVisitIsRememberedAndTheNextOneMovesTheDate() {
        ClientActivityStore store = new ClientActivityStore(
                repository, Clock.fixed(NOW, ZoneOffset.UTC));
        store.seen("token-1");
        assertEquals(NOW, store.lastSeen("token-1").orElseThrow());

        Instant later = NOW.plus(Duration.ofDays(30));
        new ClientActivityStore(repository, Clock.fixed(later, ZoneOffset.UTC))
                .seen("token-1");

        assertEquals(later, store.lastSeen("token-1").orElseThrow(),
                "One row per browser, moved in place — not a visit log");
        assertEquals(1, repository.count());
    }

    @Test
    void aBrowserNeverSeenHasNoDate() {
        ClientActivityStore store = new ClientActivityStore(
                repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(store.lastSeen("stranger").isEmpty());
    }
}
