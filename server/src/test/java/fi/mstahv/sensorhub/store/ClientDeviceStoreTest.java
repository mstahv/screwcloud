package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolationException;

import java.util.List;
import java.util.Map;

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
        assertThrows(ConstraintViolationException.class, () -> store.add(ALICE, "   "));
        assertThrows(ConstraintViolationException.class, () -> store.add(ALICE, null));
        assertThrows(ConstraintViolationException.class, () -> store.add(ALICE, "LIIANPITKA"));
    }

    @Test
    void silenceAlertsAreOffUntilAskedFor() {
        store.add(ALICE, "LAHT");

        assertFalse(store.isSilenceAlertEnabled(ALICE, "LAHT"));
        assertTrue(store.clientsWatchingForSilence().isEmpty());
    }

    @Test
    void silenceAlertsArePerBrowserAndPerDevice() {
        store.add(ALICE, "LAHT");
        store.add(ALICE, "TALO");
        store.add(BOB, "LAHT");

        store.setSilenceAlert(ALICE, "LAHT", true);

        assertTrue(store.isSilenceAlertEnabled(ALICE, "LAHT"));
        assertFalse(store.isSilenceAlertEnabled(ALICE, "TALO"));
        assertFalse(store.isSilenceAlertEnabled(BOB, "LAHT"));
        assertEquals(Map.of("LAHT", List.of(ALICE)), store.clientsWatchingForSilence());
    }

    /*
       The sweep needs every watcher of a device in one lookup, because one
       notification goes to all of them.
    */
    @Test
    void allWatchersOfADeviceAreFoundTogether() {
        store.add(ALICE, "LAHT");
        store.add(BOB, "LAHT");
        store.setSilenceAlert(ALICE, "LAHT", true);
        store.setSilenceAlert(BOB, "LAHT", true);

        List<String> watchers = store.clientsWatchingForSilence().get("LAHT");

        assertEquals(2, watchers.size());
        assertTrue(watchers.containsAll(List.of(ALICE, BOB)));
    }

    @Test
    void silenceAlertsCanBeTurnedBackOff() {
        store.add(ALICE, "LAHT");
        store.setSilenceAlert(ALICE, "LAHT", true);

        store.setSilenceAlert(ALICE, "LAHT", false);

        assertFalse(store.isSilenceAlertEnabled(ALICE, "LAHT"));
        assertTrue(store.clientsWatchingForSilence().isEmpty());
    }

    /*
       The choice hangs off the row in the browser's list, so there is nowhere to
       put it for a device that is not on that list. Silently doing nothing beats
       creating a half a row.
    */
    @Test
    void subscribingToADeviceNotOnTheListDoesNothing() {
        store.setSilenceAlert(ALICE, "EIOO", true);

        assertFalse(store.isSilenceAlertEnabled(ALICE, "EIOO"));
        assertTrue(store.devicesFor(ALICE).isEmpty());
    }

    @Test
    void removingADeviceTakesItsSilenceAlertWithIt() {
        store.add(ALICE, "LAHT");
        store.setSilenceAlert(ALICE, "LAHT", true);

        store.remove(ALICE, "LAHT");

        assertTrue(store.clientsWatchingForSilence().isEmpty());
    }

    @Test
    void devicesAreSorted() {
        store.add(ALICE, "TALO");
        store.add(ALICE, "AAAA");
        store.add(ALICE, "LAHT");

        assertEquals(List.of("AAAA", "LAHT", "TALO"), store.devicesFor(ALICE));
    }
}
