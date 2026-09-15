package fi.mstahv.sensorhub.firmware;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Keeps a checkout of the firmware, and hands out copies of the sketch to build.
 *
 * <h2>The clone is never the build directory</h2>
 *
 * <p>A build writes a {@code config.h} holding somebody's WiFi password, and
 * arduino-cli writes object files. Neither belongs in a checkout that the next
 * fetch will try to reconcile, so builds happen in a working copy of the sketch
 * and never in the clone itself.
 *
 * <p>That working copy is one directory rather than one per build, and is only
 * refreshed when the commit behind it moves — see {@link #syncInto}, where the
 * reason is the difference between a rebuild taking seconds and taking a
 * minute.
 *
 * <h2>A failed fetch is not a failed build</h2>
 *
 * <p>GitHub being unreachable means the source is a few hours old, not that
 * nothing can be built. The fetch is attempted, its failure is logged, and the
 * build proceeds from whatever the clone already has. The alternative — refusing
 * to build because a remote did not answer — would take the feature away at
 * exactly the moment the network is already annoying somebody.
 */
@Service
public class SketchSource {

    /** The sketch that gets built, relative to the repository root. */
    static final String SKETCH = "temperature-reader";

    /** The template {@link ConfigHeader} edits, inside that directory. */
    static final String TEMPLATE = "config.h.example";

    private static final Logger log = LoggerFactory.getLogger(SketchSource.class);

    private final String repository;
    private final String branch;
    private final Path clone;

    SketchSource(@Value("${sensorhub.firmware.source-repo}") String repository,
                 @Value("${sensorhub.firmware.source-branch:main}") String branch,
                 @Value("${sensorhub.firmware.work-dir:${user.home}/screwcloud-builds}") Path workDir) {
        this.repository = repository;
        this.branch = branch;
        this.clone = workDir.resolve("source");
    }

    /**
     * Brings {@code sketchRoot} up to date with the repository, and returns the
     * sketch inside it.
     *
     * <h2>Why one directory and not one per build</h2>
     *
     * <p>Copying the sketch somewhere new for each build is the obvious shape and
     * it makes every build a cold one. arduino-cli's incremental build keys on
     * paths and timestamps — the dependency files it writes name absolute paths —
     * so a sketch that moved is a sketch that has entirely changed, and the four
     * libraries and the whole core are compiled again for the sake of a config.h
     * that differs in forty bytes.
     *
     * <p>So the sketch lives in one place and stays there, and this only touches
     * it when the commit behind it has actually moved. When nothing has come from
     * git, not one timestamp changes and the next build recompiles only what the
     * new configuration really invalidates.
     *
     * <p>Safe because builds are serialised. One at a time was chosen for the
     * queue's sake; this is the second thing it buys.
     *
     * @return the sketch directory, which is where the build runs
     */
    public Path syncInto(Path sketchRoot) throws IOException {
        String head = update();
        Path sketch = sketchRoot.resolve(SKETCH);
        Path marker = sketchRoot.resolve(".source-commit");
        String previous = Files.exists(marker) ? Files.readString(marker).strip() : null;

        if (head != null && head.equals(previous) && Files.isDirectory(sketch)) {
            log.debug("Firmware source unchanged at {}; reusing the working copy", head);
            return sketch;
        }

        log.info("Firmware source is now {}; refreshing the working copy", head);
        deleteTree(sketch);
        Files.createDirectories(sketchRoot);
        copyTree(clone.resolve(SKETCH), sketch);
        if (head != null) {
            Files.writeString(marker, head);
        }
        return sketch;
    }

    /** The {@code config.h.example} of the checkout, for {@link ConfigHeader}. */
    public String template(Path sketch) throws IOException {
        return Files.readString(sketch.resolve(TEMPLATE));
    }

    /** @return the commit the checkout now sits on, or null if git would not say */
    private String update() throws IOException {
        refresh();
        String head = capture(clone, "rev-parse", "HEAD");
        return head == null || head.isBlank() ? null : head.strip();
    }

    private void refresh() throws IOException {
        if (Files.isDirectory(clone.resolve(".git"))) {
            if (git(clone.getParent(), "-C", clone.toString(), "fetch", "--depth", "1",
                    "origin", branch) != 0
                    || git(clone.getParent(), "-C", clone.toString(), "reset", "--hard",
                    "origin/" + branch) != 0) {
                log.warn("Could not update the firmware checkout at {}. Building from what "
                        + "it already has, which may be a few hours old.", clone);
            }
            return;
        }
        Files.createDirectories(clone.getParent());
        deleteTree(clone);
        /*
           A shallow clone: the builds want the current files, and nothing here
           ever looks at history. It is also the difference between a few hundred
           kilobytes and the whole repository, which carries images.
        */
        if (git(clone.getParent(), "clone", "--depth", "1", "--branch", branch,
                repository, clone.toString()) != 0) {
            throw new IOException("Could not clone " + repository + " into " + clone);
        }
    }

    /** A git command whose output is wanted rather than discarded. */
    private String capture(Path directory, String... arguments) throws IOException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", e);
        }
    }

    private int git(Path directory, String... arguments) throws IOException {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(to.resolve(from.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Deletes a directory and everything under it, quietly if it is not there.
     *
     * <p>Public because every path out of a build ends here, including the ones
     * that threw.
     */
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
