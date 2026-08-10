package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The degree-day counters running on the sensors.
 */
@Service
public class HeatSumCounterStore {

    private final HeatSumCounterRepository repository;

    HeatSumCounterStore(HeatSumCounterRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<HeatSumCounter> countersFor(String deviceId, String sensorId) {
        return repository.findByDeviceIdAndSensorIdOrderByStartedAtAsc(deviceId, sensorId);
    }

    /** Every counter on a device, for evaluating a packet that just arrived. */
    @Transactional(readOnly = true)
    public List<HeatSumCounter> countersFor(String deviceId) {
        return repository.findByDeviceId(deviceId);
    }

    /**
     * Starts a counter.
     *
     * @param comment what is hanging, or null
     * @param target degree-days; {@link HeatSumCounter#DEFAULT_TARGET} is the usual
     *        guideline
     * @param startedAt when to count from, which may be in the past for something
     *        that has already been hanging
     * @throws IllegalArgumentException if the target is not positive or the comment
     *         is too long
     */
    @Transactional
    public HeatSumCounter start(String deviceId, String sensorId, String comment, double target,
                               Instant startedAt) {
        if (target <= 0) {
            throw new IllegalArgumentException("The target has to be more than zero degree-days");
        }
        String cleaned = comment == null ? null : comment.strip();
        if (cleaned != null && cleaned.length() > HeatSumCounter.MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "A comment is at most " + HeatSumCounter.MAX_COMMENT_LENGTH + " characters");
        }
        return repository.save(new HeatSumCounter(deviceId, sensorId,
                cleaned == null || cleaned.isEmpty() ? null : cleaned, target, startedAt));
    }

    @Transactional
    public void update(long id, String comment, double target,
                       boolean alertBeforeTarget, boolean alertAtTarget) {
        repository.findById(id).ifPresent(counter -> {
            if (target <= 0) {
                throw new IllegalArgumentException("The target has to be more than zero degree-days");
            }
            /*
               A raised target un-notifies: the reader has decided the meat needs
               longer, and they should hear about the new target when it arrives
               rather than never, having already been told about the old one.
            */
            if (target > counter.getTarget()) {
                counter.setNotifiedBeforeTarget(false);
                counter.setNotifiedAtTarget(false);
            }
            counter.setComment(comment == null || comment.isBlank() ? null : comment.strip());
            counter.setTarget(target);
            counter.setAlertBeforeTarget(alertBeforeTarget);
            counter.setAlertAtTarget(alertAtTarget);
            repository.save(counter);
        });
    }

    @Transactional
    public void stop(long id) {
        repository.deleteById(id);
    }

    /** Records that a notification has gone out, so it goes out only once. */
    @Transactional
    public void markNotified(long id, boolean beforeTarget, boolean atTarget) {
        repository.findById(id).ifPresent(counter -> {
            if (beforeTarget) {
                counter.setNotifiedBeforeTarget(true);
            }
            if (atTarget) {
                counter.setNotifiedAtTarget(true);
            }
            repository.save(counter);
        });
    }

    @Transactional(readOnly = true)
    public Optional<HeatSumCounter> find(long id) {
        return repository.findById(id);
    }
}
