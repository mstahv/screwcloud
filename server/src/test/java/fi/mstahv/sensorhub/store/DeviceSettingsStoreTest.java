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
    void imageUrlRoundTrip() {
        store.setImageUrl("LAHT", "media/buildings/sauna.webp");

        assertEquals("media/buildings/sauna.webp", store.imageUrlFor("LAHT"));
    }

    @Test
    void anEmptyImageUrlClearsTheStoredOne() {
        store.setImageUrl("LAHT", "media/buildings/sauna.webp");

        store.setImageUrl("LAHT", null);

        assertNull(store.imageUrlFor("LAHT"));
    }

    /* One row carries both; changing one must not wipe the other. */
    @Test
    void nameAndImageAreIndependent() {
        store.rename("LAHT", "Mökin sauna");
        store.setImageUrl("LAHT", "media/buildings/sauna.webp");

        store.rename("LAHT", "Rantasauna");
        assertEquals("media/buildings/sauna.webp", store.imageUrlFor("LAHT"));

        store.setImageUrl("LAHT", "https://example.com/oma-kuva.jpg");
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
