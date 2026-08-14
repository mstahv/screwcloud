package org.vaadin.example.names;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What each measuring point is called, kept in a hidden CSV file in the home
 * directory of whoever runs this.
 *
 * <p>A file rather than a database because there is nothing else to store: no
 * history, no accounts, no measurements. A handful of lines that a person can read
 * and edit in a text editor is the right size for that, and it survives a
 * reinstall without a dump and restore.
 *
 * <pre>
 * # ScrewCloud sensor names
 * R0BF,Cold room
 * R1AC,"Greenhouse, south end"
 * </pre>
 *
 * <p>Keyed by the sensor identifier rather than the address, because that is what
 * the server knows a sensor as — so a name given here means the same thing as the
 * name given there.
 */
@ApplicationScoped
public class SensorNames {

    private static final Logger LOG = Logger.getLogger(SensorNames.class);

    private static final String HEADER = "# ScrewCloud sensor names: <sensor id>,<name>";

    @ConfigProperty(name = "screwcloud.names.file", defaultValue = "")
    Optional<String> configuredFile;

    private final Map<String, String> namesBySensorId = new ConcurrentHashMap<>();

    private Path file;

    /** For the container, which fills in the configuration and calls {@link #load()}. */
    public SensorNames() {
    }

    /** With the file named outright, which is what a test wants. */
    public SensorNames(Path file) {
        this.file = file;
        read();
    }

    @PostConstruct
    void load() {
        file = configuredFile.filter(name -> !name.isBlank())
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".screwcloud-sensors.csv"));
        read();
    }

    private void read() {
        if (!Files.exists(file)) {
            LOG.infof("No sensor names yet; they will be written to %s", file);
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parse(line).ifPresent(entry -> namesBySensorId.put(entry.getKey(), entry.getValue()));
            }
            LOG.infof("Read %d sensor name(s) from %s", namesBySensorId.size(), file);
        } catch (IOException e) {
            // A name is a convenience. Losing the file should not stop the reader.
            LOG.warnf("Could not read %s (%s); carrying on with identifiers only",
                    file, e.getMessage());
        }
    }

    /** The name given to this sensor, or empty if it has none. */
    public Optional<String> nameFor(String sensorId) {
        return Optional.ofNullable(namesBySensorId.get(sensorId));
    }

    /** The name to show: the one given, or the identifier itself. */
    public String displayName(String sensorId) {
        return nameFor(sensorId).orElse(sensorId);
    }

    /** Names it, or removes the name when given nothing. Written out immediately. */
    public void rename(String sensorId, String name) {
        if (name == null || name.isBlank()) {
            namesBySensorId.remove(sensorId);
        } else {
            namesBySensorId.put(sensorId, name.strip());
        }
        save();
    }

    public Path file() {
        return file;
    }

    private synchronized void save() {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        new LinkedHashMap<>(namesBySensorId).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.add(entry.getKey() + "," + quoteIfNeeded(entry.getValue())));
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write sensor names to " + file, e);
        }
    }

    /**
     * A name is free text and may well contain a comma, so it is quoted when it
     * has to be, with doubled quotes inside — the rule every spreadsheet uses.
     */
    static String quoteIfNeeded(String name) {
        if (name.contains(",") || name.contains("\"") || !name.strip().equals(name)) {
            return '"' + name.replace("\"", "\"\"") + '"';
        }
        return name;
    }

    /** One line, or empty for a comment or a blank. */
    static Optional<Map.Entry<String, String>> parse(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        int comma = trimmed.indexOf(',');
        if (comma < 1) {
            return Optional.empty();
        }
        String sensorId = trimmed.substring(0, comma).strip();
        String name = unquote(trimmed.substring(comma + 1).strip());
        return name.isEmpty() ? Optional.empty() : Optional.of(Map.entry(sensorId, name));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }
}
