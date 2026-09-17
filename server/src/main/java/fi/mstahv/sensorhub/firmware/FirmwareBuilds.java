package fi.mstahv.sensorhub.firmware;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;

import org.springframework.validation.annotation.Validated;

/**
 * Builds firmware, one at a time.
 *
 * <h2>Why a single-thread executor and not a semaphore</h2>
 *
 * <p>A {@link ThreadPoolExecutor} of one with a bounded queue gives three things
 * in one object where a semaphore would give only the first: builds happen one
 * after another, the queue cannot grow without limit, and a full queue is
 * <i>refused</i> rather than waited on. That last one matters to the reader —
 * being told "busy, try again in a minute" is a normal answer, and the
 * {@link RejectedExecutionException} is how it gets asked for.
 *
 * <h2>One at a time is also what makes it fast</h2>
 *
 * <p>Three directories are shared between builds and reused: the sketch, the
 * build directory, and arduino-cli's core cache. That is what turns a rebuild
 * from the minute a cold build takes into seconds — arduino-cli recompiles only
 * what changed, and between two builds the only thing that changed is a config.h
 * of forty bytes. The first two are per board, because a Pico's object files are
 * no use to an ESP32 build and would only be thrown away and rebuilt on every
 * alternation.
 *
 * <p>None of that would be safe with two compilers in the same directories, so
 * the serialisation chosen for the queue's sake pays for itself twice. The cost
 * is that the shared sketch holds a reader's password for as long as a build
 * takes, which is why {@code scrubConfig} runs in a {@code finally}.
 *
 * <h2>{@code @Validated}, like the stores</h2>
 *
 * <p>The constraints on {@link FirmwareRequest} run before {@link #submit} does,
 * so the rules hold no matter who calls. The form is where a reader is told about
 * them, not where they are enforced.
 */
@Service
@Validated
public class FirmwareBuilds {

    private static final Logger log = LoggerFactory.getLogger(FirmwareBuilds.class);

    private final ArduinoCli arduinoCli;
    private final SketchSource source;
    private final DeviceIdSuggester deviceIds;
    private final Path workDir;
    private final Path buildCache;
    private final Path sketchRoot;
    private final Path buildPath;
    private final Path artifacts;
    private final Duration timeout;
    private final Duration artifactTtl;
    private final ThreadPoolExecutor worker;
    private final Map<String, BuildJob> jobs = new ConcurrentHashMap<>();

    FirmwareBuilds(ArduinoCli arduinoCli, SketchSource source, DeviceIdSuggester deviceIds,
                   @Value("${sensorhub.firmware.work-dir:${user.home}/screwcloud-builds}") Path workDir,
                   @Value("${sensorhub.firmware.queue-limit:5}") int queueLimit,
                   @Value("${sensorhub.firmware.timeout:5m}") Duration timeout,
                   @Value("${sensorhub.firmware.artifact-ttl:15m}") Duration artifactTtl) {
        this.arduinoCli = arduinoCli;
        this.source = source;
        this.deviceIds = deviceIds;
        this.workDir = workDir;
        /*
           All three are shared between builds and reused, which is what makes a
           rebuild seconds rather than the minute a cold one takes. It is only
           safe because builds are serialised — see the class notes.
        */
        this.buildCache = workDir.resolve("cache");
        this.sketchRoot = workDir.resolve("sketch");
        this.buildPath = workDir.resolve("build");
        this.artifacts = workDir.resolve("artifacts");
        this.timeout = timeout;
        this.artifactTtl = artifactTtl;
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueLimit),
                runnable -> {
                    Thread thread = new Thread(runnable, "firmware-build");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Whether the page should be offered at all. */
    public boolean isAvailable() {
        return arduinoCli.isAvailable();
    }

    /** The boards this server can build for. */
    public List<Board> availableBoards() {
        return arduinoCli.availableBoards();
    }

    /**
     * Queues a build.
     *
     * @throws RejectedExecutionException when the queue is full, which is a
     *         normal condition and deserves its own answer rather than an error
     * @throws IllegalStateException when this server has no toolchain for the board
     */
    public BuildJob submit(@Valid FirmwareRequest request) {
        if (!arduinoCli.isAvailable(request.board())) {
            throw new IllegalStateException(
                    "This server cannot build firmware for the " + request.board().caption());
        }
        BuildJob job = new BuildJob(request.board(), request.deviceId(), Instant.now());
        jobs.put(job.id(), job);
        try {
            worker.execute(() -> run(job, request));
        } catch (RejectedExecutionException full) {
            jobs.remove(job.id());
            throw full;
        }
        return job;
    }

    /**
     * How many builds are ahead of this one, for a reader who is waiting.
     *
     * <p>Counted rather than stored, because a position is only true for as long
     * as it takes to read it.
     */
    public long queuePosition(BuildJob job) {
        return jobs.values().stream()
                .filter(other -> other.state() == BuildJob.State.QUEUED)
                .filter(other -> other.requestedAt().isBefore(job.requestedAt()))
                .count();
    }

    /**
     * Ends a job the reader has finished with, and deletes what it built.
     *
     * <p>The file holds a WiFi password in clear text, so the moment it has been
     * downloaded is the moment it should stop existing.
     */
    public void discard(BuildJob job) {
        job.onChange(null);
        job.discardArtifact();
        jobs.remove(job.id());
    }

    private void run(BuildJob job, FirmwareRequest request) {
        Path sketch = null;
        try {
            job.progress(BuildJob.State.RUNNING, "Fetching the current firmware");
            Files.createDirectories(buildCache);
            Files.createDirectories(artifacts);
            sketch = source.syncInto(sketchRoot, job.board());

            job.progress(BuildJob.State.RUNNING, "Writing the configuration");
            writeConfig(request, sketch);

            job.progress(BuildJob.State.RUNNING, "Compiling — this takes a moment");
            compile(job, sketch);
        } catch (IOException | RuntimeException failure) {
            /*
               The reader is told that it failed and nothing else. The source is
               ours and the configuration is generated, so a failure here is the
               server's fault rather than theirs — and compiler output is the one
               place where what they typed could come back at them.
            */
            log.error("Firmware build {} for {} failed", job.id(), job.deviceId(), failure);
            job.progress(BuildJob.State.FAILED,
                    "The build failed. This has been logged for somebody to look at.");
        } finally {
            /*
               The sketch directory is reused, so the config.h in it would
               otherwise sit there holding one reader's WiFi password until the
               next build happened to overwrite it. Put back the template, on
               every path out including the ones that threw.
            */
            scrubConfig(sketch);
        }
    }

    private void scrubConfig(Path sketch) {
        if (sketch == null) {
            return;
        }
        try {
            Files.copy(sketch.resolve(SketchSource.TEMPLATE), sketch.resolve("config.h"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            log.warn("Could not clear the configuration in {}", sketch, failure);
        }
    }

    private void writeConfig(FirmwareRequest request, Path sketch) throws IOException {
        String template = source.template(sketch);
        try (BufferedWriter out = Files.newBufferedWriter(sketch.resolve("config.h"))) {
            ConfigHeader.write(request, template, out);
        }
    }

    private void compile(BuildJob job, Path sketch) throws IOException {
        Path logFile = workDir.resolve("last-compile.log");
        Board board = job.board();
        List<String> command = List.of(arduinoCli.executable(), "compile",
                "--fqbn", board.fqbn(),
                "--build-path", buildPath.resolve(board.sketch()).toString(),
                "--build-cache-path", buildCache.toString(),
                sketch.toString());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                /*
                   To a file, not a pipe. A pipe nobody drains fills and blocks
                   the child, and a compiler is talkative enough to fill one —
                   while the output is wanted for the server log rather than
                   live, so there is nothing to gain by reading it as it comes.
                */
                .redirectOutput(logFile.toFile());
        /*
           Set rather than inherited. ARDUINO_DIRECTORIES_DATA decides where the
           core is found, and inheriting whatever the service happened to start
           with is how a build works in development and not in production.
        */
        builder.environment().put("ARDUINO_DIRECTORIES_DATA", arduinoCli.dataDir().toString());

        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.error("Firmware build {} for {} timed out after {}",
                        job.id(), job.deviceId(), timeout);
                job.progress(BuildJob.State.TIMED_OUT,
                        "The build took longer than it should and was stopped.");
                return;
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while compiling", interrupted);
        }

        if (process.exitValue() != 0) {
            log.error("Firmware build {} for {} failed; arduino-cli said:\n{}",
                    job.id(), job.deviceId(), readLog(logFile));
            job.progress(BuildJob.State.FAILED,
                    "The build failed. This has been logged for somebody to look at.");
            return;
        }

        Path built = findImage(buildPath.resolve(board.sketch()), board);
        if (built == null) {
            log.error("Firmware build {} for {} produced no {} under {}",
                    job.id(), job.deviceId(), board.imageSuffix(), buildPath);
            job.progress(BuildJob.State.FAILED,
                    "The build produced nothing. This has been logged.");
            return;
        }

        /*
           Out of the shared build directory before the next build overwrites it,
           and into one of this job's own that discard() can delete whole. The
           ESP32's image loses its padding on the way — see MergedImage.
        */
        Path kept = artifacts.resolve(job.id()).resolve(job.fileName());
        Files.createDirectories(kept.getParent());
        if (board.padded()) {
            try (OutputStream out = Files.newOutputStream(kept)) {
                MergedImage.writeTrimmed(built, out);
            }
        } else {
            Files.copy(built, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        /*
           Only now, because an identifier spent on a build that failed is an
           identifier nobody is using.
        */
        deviceIds.reserve(job.deviceId());
        log.info("Firmware build {} for {} finished", job.id(), job.deviceId());
        job.succeeded(kept);
    }

    private static Path findImage(Path buildPath, Board board) throws IOException {
        if (!Files.isDirectory(buildPath)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(buildPath)) {
            return files.filter(path -> path.getFileName().toString().endsWith(board.imageSuffix()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static String readLog(Path logFile) {
        try {
            return Files.readString(logFile);
        } catch (IOException unreadable) {
            return "(the compiler's output could not be read: " + unreadable + ")";
        }
    }


    /**
     * Clears artifacts nobody came back for.
     *
     * <p>They are deleted on download, so this is only for the reader who closed
     * the tab. It runs often because of what the files hold, not because of what
     * they weigh.
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void sweep() {
        Instant cutoff = Instant.now().minus(artifactTtl);
        List<BuildJob> stale = new ArrayList<>();
        for (BuildJob job : jobs.values()) {
            if (job.isFinished() && job.finishedAt() != null && job.finishedAt().isBefore(cutoff)) {
                stale.add(job);
            }
        }
        stale.sort(Comparator.comparing(BuildJob::requestedAt));
        stale.forEach(this::discard);
        if (!stale.isEmpty()) {
            log.info("Swept {} firmware artifact(s) nobody downloaded", stale.size());
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
        jobs.values().forEach(BuildJob::discardArtifact);
    }
}
