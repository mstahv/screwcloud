package org.vaadin.example.names;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sensors the reader has asked not to see: the neighbour's tag that this
 * radio hears just as well as its owner's does.
 *
 * <p>Ignoring is about the page, not the data — a tag keeps being heard, keeps
 * going into history and keeps being relayed upstream; only its card is gone.
 * That is what makes restoring complete: the sensor comes back with everything
 * it gathered while it was out of sight.
 *
 * <p>A hidden file next to the names file, one identifier per line, for the
 * same reasons {@link SensorNames} gives: nothing else to store, readable in a
 * text editor, survives a reinstall.
 */
@ApplicationScoped
public class IgnoredSensors {

    private static final Logger LOG = Logger.getLogger(IgnoredSensors.class);

    private static final String HEADER = "# ScrewCloud ignored sensors: <sensor id>";

    @ConfigProperty(name = "screwcloud.ignored.file", defaultValue = "")
    Optional<String> configuredFile;

    private final Set<String> ignoredIds = ConcurrentHashMap.newKeySet();

    private Path file;

    /** For the container, which fills in the configuration and calls {@link #load()}. */
    public IgnoredSensors() {
    }

    /** With the file named outright, which is what a test wants. */
    public IgnoredSensors(Path file) {
        this.file = file;
        read();
    }

    @PostConstruct
    void load() {
        file = configuredFile.filter(name -> !name.isBlank())
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), ".screwcloud-ignored.csv"));
        read();
    }

    private void read() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    ignoredIds.add(trimmed);
                }
            }
            LOG.infof("Read %d ignored sensor(s) from %s", ignoredIds.size(), file);
        } catch (IOException e) {
            // Being ignored is a convenience. Losing the file should not stop the reader.
            LOG.warnf("Could not read %s (%s); showing everything",
                    file, e.getMessage());
        }
    }

    public boolean isIgnored(String sensorId) {
        return ignoredIds.contains(sensorId);
    }

    /** Asks not to see this sensor. Written out immediately. */
    public void ignore(String sensorId) {
        ignoredIds.add(sensorId);
        save();
    }

    /** Puts an ignored sensor back on the page. Written out immediately. */
    public void restore(String sensorId) {
        ignoredIds.remove(sensorId);
        save();
    }

    /** Everything currently ignored, sorted so the page's list holds still. */
    public List<String> all() {
        return ignoredIds.stream().sorted().toList();
    }

    private synchronized void save() {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.addAll(all());
        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write ignored sensors to " + file, e);
        }
    }
}
