package fi.mstahv.sensorhub.store;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Remembers when each browser token was last seen.
 *
 * <p>The views call {@link #seen} whenever a page resolves its token, which is
 * every visit — and that is the entire write side. The read side is
 * {@link RetentionSweep}, which uses the dates to forget browsers that have
 * stopped coming.
 */
@Service
@Validated
public class ClientActivityStore {

    private final ClientActivityRepository repository;
    private final Clock clock;

    @Autowired
    ClientActivityStore(ClientActivityRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ClientActivityStore(ClientActivityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * The browser is here now. One row per token, updated in place — a visit
     * log would be a disk-filler of its own, and nothing here asks anything
     * finer than "when was the last time".
     */
    @Transactional
    public void seen(@NotBlank @Size(max = 64) String clientId) {
        repository.findById(clientId).ifPresentOrElse(
                activity -> {
                    activity.setLastSeen(clock.instant());
                    repository.save(activity);
                },
                () -> repository.save(new ClientActivity(clientId, clock.instant())));
    }

    /** When the browser was last here, or empty for one never seen. */
    @Transactional(readOnly = true)
    public Optional<Instant> lastSeen(String clientId) {
        return repository.findById(clientId).map(ClientActivity::getLastSeen);
    }
}
