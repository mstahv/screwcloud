package fi.mstahv.sensorhub.firmware;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Finds the compiler, and decides whether this server can build firmware at all.
 *
 * <h2>Configuration is an override, not a requirement</h2>
 *
 * <p>Nothing has to be set for this to work. The usual install puts
 * {@code arduino-cli} somewhere already on the path and its data in the service
 * user's home, and both of those are looked for without being told. A deployment
 * that put them elsewhere says so with {@code sensorhub.firmware.arduino-cli},
 * and that is the only reason the setting exists.
 *
 * <p>The distinction the log keeps is between <b>absent</b> and <b>wrong</b>. A
 * server with no toolchain is not misconfigured — the feature is optional and
 * simply not offered, the same way notifications are absent without VAPID keys —
 * so that is one INFO line. A path somebody set explicitly and got wrong is a
 * mistake they want to hear about, so that is a WARN naming what was tried.
 *
 * <h2>It checks for the core, not just the binary</h2>
 *
 * <p>Asking only whether {@code arduino-cli} answers would let the page be
 * offered on a machine that cannot build for a Pico — the binary is one download
 * and the arduino-pico core is the several hundred megabytes that actually
 * matter. Both are checked here, at startup, because every way this is wrong
 * looks fine until something asks the compiler to work, and the right time to
 * find that out is not while somebody waits for a download.
 */
@Service
public class ArduinoCli {

    /** The platform the firmware is built for, and what a usable install has. */
    static final String CORE = "rp2040:rp2040";

    private static final Logger log = LoggerFactory.getLogger(ArduinoCli.class);

    /**
     * Where to look when nobody said. The bare name first, so an install on the
     * path wins without anybody configuring anything; then the two places an
     * install script is likely to have put it.
     */
    private static final List<String> CANDIDATES =
            List.of("arduino-cli", "%s/bin/arduino-cli".formatted(System.getProperty("user.home")),
                    "/usr/local/bin/arduino-cli");

    private final String executable;
    private final Path dataDir;
    private final boolean available;

    ArduinoCli(@Value("${sensorhub.firmware.arduino-cli:}") String configured,
               @Value("${sensorhub.firmware.data-dir:${user.home}/.arduino15}") Path dataDir) {
        this.dataDir = dataDir;
        this.executable = configured.isBlank() ? discover() : configured;
        this.available = executable != null && hasCore();
        report(configured);
    }

    /** Whether the build page should be offered at all. */
    public boolean isAvailable() {
        return available;
    }

    /** The binary to run, once {@link #isAvailable()} has said there is one. */
    public String executable() {
        return executable;
    }

    /**
     * Where the core and the libraries live.
     *
     * <p>Passed to every build as {@code ARDUINO_DIRECTORIES_DATA} rather than
     * inherited, because inheriting whatever the service happened to start with
     * is how a build works in development and not in production.
     */
    public Path dataDir() {
        return dataDir;
    }

    private String discover() {
        for (String candidate : CANDIDATES) {
            if (answersVersion(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean answersVersion(String candidate) {
        return run(List.of(candidate, "version"), 10) == 0;
    }

    private boolean hasCore() {
        if (!answersVersion(executable)) {
            return false;
        }
        /*
           Reads the local install rather than the network, so this is fast, but
           it is given room: the first call after an install can be doing
           bookkeeping the next thousand will not.
        */
        return run(List.of(executable, "core", "list"), 60) == 0 && coreIsInstalled();
    }

    private boolean coreIsInstalled() {
        // The core's own directory is the cheapest honest answer, and needs no
        // parsing of output whose format is not ours.
        return Files.isDirectory(dataDir.resolve("packages").resolve("rp2040"));
    }

    private int run(List<String> command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            return process.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void report(String configured) {
        if (available) {
            log.info("Firmware builds available: {} with {} under {}", executable, CORE, dataDir);
        } else if (!configured.isBlank()) {
            log.warn("Firmware builds unavailable: sensorhub.firmware.arduino-cli is set to '{}', "
                    + "but it does not answer or the {} core is missing from {}. "
                    + "See \"Building firmware on the server\" in the README.",
                    configured, CORE, dataDir);
        } else if (executable != null) {
            log.warn("Firmware builds unavailable: {} answers, but the {} core is not installed "
                    + "under {}. See \"Building firmware on the server\" in the README.",
                    executable, CORE, dataDir);
        } else {
            log.info("Firmware builds unavailable: no arduino-cli found. The feature is optional "
                    + "and everything else works as before.");
        }
    }
}
