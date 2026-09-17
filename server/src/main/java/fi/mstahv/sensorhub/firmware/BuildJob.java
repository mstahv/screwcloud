package fi.mstahv.sensorhub.firmware;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One build, from queued to downloaded.
 *
 * <p>Mutable and shared between the worker thread that advances it and the view
 * that watches, so every field that changes is {@code volatile} and the listener
 * is called rather than polled. The view does nothing with the values directly —
 * it is told to look again, and reads them under the session lock, which is the
 * same arrangement {@code DeviceUpdates} uses for measurements.
 */
public class BuildJob {

    /** Where a build is, in the order it gets there. */
    public enum State {
        QUEUED, RUNNING, SUCCEEDED, FAILED, TIMED_OUT
    }

    private final String id = UUID.randomUUID().toString();
    private final Board board;
    private final String deviceId;
    private final Instant requestedAt;

    private volatile State state = State.QUEUED;
    private volatile String message = "Waiting for a free moment";
    private volatile Path artifact;
    private volatile Instant finishedAt;
    private volatile Consumer<BuildJob> listener = job -> { };

    BuildJob(Board board, String deviceId, Instant requestedAt) {
        this.board = board;
        this.deviceId = deviceId;
        this.requestedAt = requestedAt;
    }

    public String id() {
        return id;
    }

    /** What the image is for, which decides how it gets onto the device. */
    public Board board() {
        return board;
    }

    public String deviceId() {
        return deviceId;
    }

    public State state() {
        return state;
    }

    /** What to show a reader who is waiting. Never a compiler's own words. */
    public String message() {
        return message;
    }

    public boolean isFinished() {
        return state != State.QUEUED && state != State.RUNNING;
    }

    public boolean isDownloadable() {
        return state == State.SUCCEEDED && artifact != null && Files.exists(artifact);
    }

    Instant requestedAt() {
        return requestedAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    /**
     * The built image.
     *
     * <p>Into a stream rather than back as a {@code byte[]}: this is on its way
     * to a browser, and there is no reason for a megabyte of it to exist twice.
     */
    public void writeTo(OutputStream out) throws IOException {
        if (!isDownloadable()) {
            throw new IOException("This build has nothing to download");
        }
        Files.copy(artifact, out);
    }

    /** What the downloaded file should be called. */
    public String fileName() {
        return board.fileName(deviceId);
    }

    /**
     * Registers the one watcher. Replacing rather than adding, because a job
     * belongs to the session that asked for it and a second watcher would be a
     * bug rather than a feature.
     */
    public void onChange(Consumer<BuildJob> listener) {
        this.listener = listener == null ? job -> { } : listener;
        this.listener.accept(this);
    }

    void progress(State state, String message) {
        this.state = state;
        this.message = message;
        if (isFinished() && finishedAt == null) {
            finishedAt = Instant.now();
        }
        listener.accept(this);
    }

    void succeeded(Path artifact) {
        this.artifact = artifact;
        progress(State.SUCCEEDED, "Ready");
    }

    Path artifact() {
        return artifact;
    }

    /**
     * Forgets the built image.
     *
     * <p>Called on download and by the sweep, because the file holds somebody's
     * WiFi password in clear text and should not outlive the errand it was for.
     */
    void discardArtifact() {
        Path built = artifact;
        artifact = null;
        if (built != null) {
            try {
                SketchSource.deleteTree(built.getParent());
            } catch (IOException ignored) {
                // A file that could not be deleted is for the sweep to complain about.
            }
        }
    }
}
