package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;

@DatabaseTest
@Import({TestDatabase.class, ClientDeviceStore.class})
class ClientDeviceStoreTest {

    private static final String ALICE = "client-alice";
    private static final String BOB = "client-bob";

    @Autowired
    private ClientDeviceStore store;

    @Test
    void listsOnlyOwnDevices() {
        store.add(ALICE, "LAHT");
        store.add(BOB, "TALO");

        assertEquals(List.of("LAHT"), store.devicesFor(ALICE));
        assertEquals(List.of("TALO"), store.devicesFor(BOB));
    }

    @Test
    void listIsEmptyForUnknownClient() {
        assertTrue(store.devicesFor("client-tuntematon").isEmpty());
    }

    @Test
    void sameDeviceCanBeSharedBySeveralClients() {
        store.add(ALICE, "LAHT");
        store.add(BOB, "LAHT");

        assertEquals(List.of("LAHT"), store.devicesFor(ALICE));
        assertEquals(List.of("LAHT"), store.devicesFor(BOB));
    }

    @Test
    void addingTwiceDoesNotDuplicate() {
        store.add(ALICE, "LAHT");
        store.add(ALICE, "LAHT");

        assertEquals(List.of("LAHT"), store.devicesFor(ALICE));
    }

    @Test
    void deviceIdIsNormalisedToUpperCase() {
        assertEquals("LAHT", store.add(ALICE, " laht "));

        // The same device in lower case must not create another row.
        store.add(ALICE, "Laht");

        assertEquals(List.of("LAHT"), store.devicesFor(ALICE));
    }

    @Test
    void removingAffectsOnlyOneClient() {
        store.add(ALICE, "LAHT");
        store.add(BOB, "LAHT");

        store.remove(ALICE, "LAHT");

        assertTrue(store.devicesFor(ALICE).isEmpty());
        assertEquals(List.of("LAHT"), store.devicesFor(BOB));
    }

    @Test
    void rejectsEmptyAndOverlongDeviceIds() {
        assertThrows(IllegalArgumentException.class, () -> store.add(ALICE, "   "));
        assertThrows(IllegalArgumentException.class, () -> store.add(ALICE, null));
        assertThrows(IllegalArgumentException.class, () -> store.add(ALICE, "LIIANPITKA"));
    }

    @Test
    void devicesAreSorted() {
        store.add(ALICE, "TALO");
        store.add(ALICE, "AAAA");
        store.add(ALICE, "LAHT");

        assertEquals(List.of("AAAA", "LAHT", "TALO"), store.devicesFor(ALICE));
    }
}
