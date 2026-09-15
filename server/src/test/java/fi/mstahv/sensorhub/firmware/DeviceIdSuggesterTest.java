package fi.mstahv.sensorhub.firmware;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;
import fi.mstahv.sensorhub.store.FirmwareBuildRepository;
import fi.mstahv.sensorhub.store.MeasurementStore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The suggestion's job is to be free, and "free" is the part with a trap in it:
 * an identifier built for this morning has sent nothing, so the measurements do
 * not know it.
 */
@DatabaseTest
@Import({TestDatabase.class, MeasurementStore.class, DeviceIdSuggester.class})
class DeviceIdSuggesterTest {

    @Autowired
    private DeviceIdSuggester suggester;

    @Autowired
    private FirmwareBuildRepository builds;

    @Test
    void suggestsSomethingTheDeviceIdRuleAccepts() {
        String suggested = suggester.suggest().orElseThrow();

        assertTrue(suggested.matches("[A-Z0-9]{4}"), suggested);
    }

    @Test
    void leavesOutTheCharactersThatLookLikeEachOther() {
        Set<Character> used = IntStream.range(0, 200)
                .mapToObj(i -> suggester.suggest().orElseThrow())
                .flatMap(id -> id.chars().mapToObj(c -> (char) c))
                .collect(Collectors.toSet());

        // On a small display across a room these four are two characters, not four.
        assertFalse(used.contains('I'), "I is too like 1");
        assertFalse(used.contains('O'), "O is too like 0");
        assertFalse(used.contains('0'), "0 is too like O");
        assertFalse(used.contains('1'), "1 is too like I");
    }

    @Test
    void anIdentifierBuiltForIsTakenBeforeTheDeviceHasEverReported() {
        String reserved = suggester.suggest().orElseThrow();
        suggester.reserve(reserved);

        // Nothing has arrived from it — the measurements have never heard of it —
        // and it must still not be handed to the next person who asks.
        assertTrue(suggester.isTaken(reserved));
        assertTrue(IntStream.range(0, 200)
                .mapToObj(i -> suggester.suggest().orElseThrow())
                .noneMatch(reserved::equals));
    }

    @Test
    void takenDoesNotCareAboutCaseOrSurroundingSpace() {
        suggester.reserve("AB2C");

        assertTrue(suggester.isTaken("ab2c"));
        assertTrue(suggester.isTaken("  AB2C "));
    }

    @Test
    void rebuildingForTheSameDeviceTouchesTheRowRatherThanAddingOne() {
        suggester.reserve("AB2C");
        var first = builds.findByDeviceId("AB2C").orElseThrow().getBuiltAt();
        suggester.reserve("ab2c");

        assertTrue(builds.findDeviceIds().stream().filter("AB2C"::equals).count() == 1,
                "one identifier, one row");
        assertFalse(builds.findByDeviceId("AB2C").orElseThrow().getBuiltAt().isBefore(first));
    }
}
