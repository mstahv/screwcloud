package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.DeviceId;
import fi.mstahv.sensorhub.validation.SensorId;

/**
 * The degree-day counters running on the sensors.
 *
 * <p>What a counter may look like is stated once, on {@link HeatSumCounter}. The
 * parameters here repeat it only where a value cannot reach that entity to be
 * judged — a rejected target should not have created a counter first.
 */
@Service
@Validated
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
     * @throws jakarta.validation.ConstraintViolationException if the target is not
     *         positive or the comment is too long
     */
    @Transactional
    public HeatSumCounter start(@NotBlank @DeviceId String deviceId,
                                @NotNull @SensorId String sensorId,
                                @Size(max = HeatSumCounter.MAX_COMMENT_LENGTH) String comment,
                                @Positive(message = "The target has to be more than zero degree-days")
                                double target,
                                @NotNull @PastOrPresent Instant startedAt) {
        String cleaned = comment == null ? null : comment.strip();
        return repository.save(new HeatSumCounter(deviceId, sensorId,
                cleaned == null || cleaned.isEmpty() ? null : cleaned, target, startedAt));
    }

    @Transactional
    public void update(long id, @Size(max = HeatSumCounter.MAX_COMMENT_LENGTH) String comment,
                       @Positive(message = "The target has to be more than zero degree-days")
                       double target,
                       boolean alertBeforeTarget, boolean alertAtTarget) {
        repository.findById(id).ifPresent(counter -> {
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
