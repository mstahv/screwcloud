package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;

@DatabaseTest
@Import({TestDatabase.class, DeviceSettingsStore.class})
class DeviceSettingsStoreTest {

    @Autowired
    private DeviceSettingsStore store;

    @Test
    void anUnnamedDeviceGoesByItsIdentifier() {
        assertNull(store.nameFor("LAHT"));
        assertEquals("LAHT", store.displayNameFor("LAHT"));
    }

    @Test
    void nameRoundTrip() {
        store.rename("LAHT", "Mökin sauna");

        assertEquals("Mökin sauna", store.nameFor("LAHT"));
        assertEquals("Mökin sauna", store.displayNameFor("LAHT"));
    }

    /* Emptying the field is how a name is taken back, so blank means clear. */
    @Test
    void anEmptyNameClearsTheStoredOne() {
        store.rename("LAHT", "Mökin sauna");

        store.rename("LAHT", "   ");

        assertNull(store.nameFor("LAHT"));
        assertEquals("LAHT", store.displayNameFor("LAHT"));
    }

    @Test
    void namesAreScopedToTheDevice() {
        store.rename("LAHT", "Mökin sauna");

        assertNull(store.nameFor("TALO"));
    }

    @Test
    void iconRoundTrip() {
        store.setIcon("LAHT", "sauna");

        assertEquals("sauna", store.iconFor("LAHT"));
    }

    @Test
    void anEmptyIconClearsTheStoredOne() {
        store.setIcon("LAHT", "sauna");

        store.setIcon("LAHT", null);

        assertNull(store.iconFor("LAHT"));
    }

    /* One row carries both; changing one must not wipe the other. */
    @Test
    void nameAndIconAreIndependent() {
        store.rename("LAHT", "Mökin sauna");
        store.setIcon("LAHT", "sauna");

        store.rename("LAHT", "Rantasauna");
        assertEquals("sauna", store.iconFor("LAHT"));

        store.setIcon("LAHT", "cabin");
        assertEquals("Rantasauna", store.nameFor("LAHT"));
    }

    /*
       Validation lives in the store rather than only in the form, so the rule
       holds regardless of who calls.
    */
    @Test
    void aNameLongerThanItsColumnIsRejected() {
        assertThrows(ConstraintViolationException.class,
                () -> store.rename("LAHT", "x".repeat(65)));

        assertNull(store.nameFor("LAHT"));
    }
}
