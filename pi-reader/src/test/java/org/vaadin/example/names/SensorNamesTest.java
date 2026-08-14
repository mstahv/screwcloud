package org.vaadin.example.names;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The name file, which is the only thing this application keeps between runs.
 *
 * <p>It is meant to be opened in an editor, so the tests are as much about what it
 * looks like as about what round trips.
 */
class SensorNamesTest {

    @Test
    void anameGivenIsThereAfterARestart(@TempDir Path directory) {
        Path file = directory.resolve("names.csv");

        SensorNames first = load(file);
        first.rename("R0BF", "Cold room");

        assertEquals("Cold room", load(file).displayName("R0BF"));
    }

    /** An unnamed sensor shows as itself rather than as a blank. */
    @Test
    void anUnnamedSensorIsShownByItsIdentifier(@TempDir Path directory) {
        SensorNames names = load(directory.resolve("names.csv"));

        assertEquals("R0BF", names.displayName("R0BF"));
        assertTrue(names.nameFor("R0BF").isEmpty());
    }

    @Test
    void anEmptyNameRemovesTheName(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("names.csv");
        SensorNames names = load(file);
        names.rename("R0BF", "Cold room");

        names.rename("R0BF", "  ");

        assertEquals("R0BF", names.displayName("R0BF"));
        assertTrue(Files.readAllLines(file).stream().noneMatch(line -> line.contains("Cold room")));
    }

    /** A name with a comma is ordinary; it must not split the line. */
    @Test
    void aNameContainingACommaSurvives(@TempDir Path directory) {
        Path file = directory.resolve("names.csv");
        load(file).rename("R0BF", "Greenhouse, south end");

        assertEquals("Greenhouse, south end", load(file).displayName("R0BF"));
    }

    @Test
    void aNameContainingAQuoteSurvives(@TempDir Path directory) {
        Path file = directory.resolve("names.csv");
        load(file).rename("R0BF", "The \"cold\" room");

        assertEquals("The \"cold\" room", load(file).displayName("R0BF"));
    }

    /** The file is meant to be read and edited by a person. */
    @Test
    void theFileIsPlainAndCommented(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("names.csv");
        SensorNames names = load(file);
        names.rename("R1AC", "Sauna");
        names.rename("R0BF", "Cold room");

        assertEquals("""
                # ScrewCloud sensor names: <sensor id>,<name>
                R0BF,Cold room
                R1AC,Sauna""",
                Files.readString(file).strip());
    }

    /** Hand edits are read back, including the shapes a person would type. */
    @Test
    void aHandWrittenFileIsRead(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("names.csv");
        Files.writeString(file, """
                # my tags
                R0BF,Cold room

                R1AC , Sauna
                R2CD,"Greenhouse, south end"
                broken line with no comma
                R3EF,
                """);

        SensorNames names = load(file);

        assertEquals("Cold room", names.displayName("R0BF"));
        assertEquals("Sauna", names.displayName("R1AC"));
        assertEquals("Greenhouse, south end", names.displayName("R2CD"));
        assertEquals("R3EF", names.displayName("R3EF"), "a line with no name names nothing");
    }

    /** A file that cannot be read costs the names, not the reader. */
    @Test
    void anUnreadableFileIsNotFatal(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("subdirectory");
        Files.createDirectory(file);

        SensorNames names = load(file);

        assertEquals("R0BF", names.displayName("R0BF"));
    }

    private static SensorNames load(Path file) {
        return new SensorNames(file);
    }
}
