package fi.mstahv.sensorhub.store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.DeviceId;
import fi.mstahv.sensorhub.validation.SensorId;

/**
 * User settings for sensors: the display name and the gauge's temperature bands.
 *
 * <p>Separate from {@link MeasurementStore}, because settings are rarely
 * changing user input rather than a stream of measurements.
 *
 * <p>The rules on what may be stored are the constraints on the parameters and on
 * {@link SensorThresholds}; {@code @Validated} runs them before the method body.
 */
@Service
@Validated
public class SensorSettingsStore {

    private final SensorSettingsRepository repository;

    SensorSettingsStore(SensorSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the sensor's name, or null if none has been given
     */
    @Transactional(readOnly = true)
    public String nameFor(String deviceId, String sensorId) {
        return repository.findByDeviceIdAndSensorId(deviceId, sensorId)
                .map(SensorSettings::getName)
                .filter(name -> !name.isBlank())
                .orElse(null);
    }

    /**
     * Sets the name. An empty or whitespace-only value clears it, after which
     * the UI shows the sensor identifier again.
     */
    @Transactional
    public void rename(@NotBlank @DeviceId String deviceId, @NotNull @SensorId String sensorId,
                       @Size(max = 64) String name) {
        String cleaned = name == null ? null : name.strip();
        SensorSettings settings = load(deviceId, sensorId);
        settings.setName(cleaned == null || cleaned.isEmpty() ? null : cleaned);
        repository.save(settings);
    }

    /**
     * @return the sensor's temperature bands, or {@link SensorThresholds#NONE} if
     *         none are configured
     */
    @Transactional(readOnly = true)
    public SensorThresholds thresholdsFor(String deviceId, String sensorId) {
        return repository.findByDeviceIdAndSensorId(deviceId, sensorId)
                .map(SensorSettings::getThresholds)
                .orElse(SensorThresholds.NONE);
    }

    /**
     * Stores the temperature bands. Passing {@link SensorThresholds#NONE} clears
     * them, after which the gauge falls back to its stock range and colours.
     *
     * @throws jakarta.validation.ConstraintViolationException if the values are
     *         partially filled or out of order
     */
    @Transactional
    public void setThresholds(@NotBlank @DeviceId String deviceId,
                              @NotNull @SensorId String sensorId,
                              @Valid @NotNull SensorThresholds thresholds) {
        SensorSettings settings = load(deviceId, sensorId);
        settings.setThresholds(thresholds);
        repository.save(settings);
    }

    private SensorSettings load(String deviceId, String sensorId) {
        return repository.findByDeviceIdAndSensorId(deviceId, sensorId)
                .orElseGet(() -> new SensorSettings(deviceId, sensorId));
    }
}
