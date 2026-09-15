package fi.mstahv.sensorhub.firmware;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fi.mstahv.sensorhub.store.FirmwareBuild;
import fi.mstahv.sensorhub.store.FirmwareBuildRepository;
import fi.mstahv.sensorhub.store.MeasurementStore;
import fi.mstahv.sensorhub.validation.DeviceId;

/**
 * Suggests a device identifier nobody is using, and remembers the ones handed
 * out.
 *
 * <p>The reader still chooses. A name somebody picked is worth more than a name
 * a machine picked, and the field is theirs to overwrite — this only fills it in
 * so that choosing is optional and the common case is pressing the button.
 *
 * <h2>The alphabet leaves out four characters</h2>
 *
 * <p>{@code I}, {@code O}, {@code 0} and {@code 1} are gone, which leaves 32
 * symbols and about a million identifiers. The reason is where these are read:
 * off a 128-pixel display, across a room, and typed back into a phone. That is
 * exactly the situation in which {@code 0} and {@code O} are the same character
 * and an argument follows. Thirty-two is also a power of two, so the symbols
 * come out of {@link SecureRandom} without the modulo bias that 36 would bring.
 *
 * <h2>Taken means two things</h2>
 *
 * <p>An identifier is taken if a device has reported under it <b>or</b> if
 * firmware has been built for it. The second half is the one easily forgotten
 * and the reason {@link FirmwareBuild} exists: the measurements only know a
 * device once a packet has arrived, so one built in the morning and flashed in
 * the evening is invisible to them all day. Without the reservations, two people
 * asking on the same afternoon get the same identifier — which is the collision
 * this whole idea was meant to remove.
 */
@Service
public class DeviceIdSuggester {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /**
     * Enough that exhausting them is not a real outcome, few enough that a
     * database gone strange does not spin here. With a million identifiers and
     * the handful this will ever hold, the first attempt is the one that works.
     */
    private static final int ATTEMPTS = 50;

    private final MeasurementStore measurements;
    private final FirmwareBuildRepository builds;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    DeviceIdSuggester(MeasurementStore measurements, FirmwareBuildRepository builds) {
        this(measurements, builds, Clock.systemUTC());
    }

    /** The clock is a parameter so a test can decide what "now" is. */
    DeviceIdSuggester(MeasurementStore measurements, FirmwareBuildRepository builds, Clock clock) {
        this.measurements = measurements;
        this.builds = builds;
        this.clock = clock;
    }

    /**
     * A free identifier, or empty in the case that will not happen.
     *
     * <p>Empty rather than an exception because the caller has something sensible
     * to do with it: leave the field blank and let the reader type their own. A
     * suggestion is a convenience, and a convenience that cannot be provided is
     * not a failure worth an error page.
     */
    @Transactional(readOnly = true)
    public Optional<String> suggest() {
        Set<String> taken = taken();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String candidate = generate();
            if (!taken.contains(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Whether an identifier the reader typed is already spoken for. */
    @Transactional(readOnly = true)
    public boolean isTaken(String deviceId) {
        return deviceId != null && taken().contains(normalise(deviceId));
    }

    /**
     * Records that firmware exists for this identifier.
     *
     * <p>Called when a build succeeds, not when one is requested: an identifier
     * spent on a build that failed is an identifier nobody is using.
     *
     * <p>Rebuilding for a device somebody already has is ordinary — a new
     * password, a moved network — so the row is touched rather than duplicated.
     */
    @Transactional
    public void reserve(String deviceId) {
        String normalised = normalise(deviceId);
        Instant now = Instant.now(clock);
        builds.findByDeviceId(normalised)
                .ifPresentOrElse(existing -> existing.setBuiltAt(now),
                        () -> builds.save(new FirmwareBuild(normalised, now)));
    }

    private Set<String> taken() {
        Set<String> taken = new HashSet<>();
        measurements.deviceIds().forEach(id -> taken.add(normalise(id)));
        builds.findDeviceIds().forEach(id -> taken.add(normalise(id)));
        return taken;
    }

    private String generate() {
        StringBuilder id = new StringBuilder(DeviceId.MAX_LENGTH);
        for (int i = 0; i < DeviceId.MAX_LENGTH; i++) {
            id.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return id.toString();
    }

    /** As the stores compare them: stripped, and without caring about case. */
    private static String normalise(String deviceId) {
        return deviceId.strip().toUpperCase();
    }
}
