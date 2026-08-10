package fi.mstahv.sensorhub.store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User settings for sensors: the display name and the gauge's temperature bands.
 *
 * <p>Separate from {@link MeasurementStore}, because settings are rarely
 * changing user input rather than a stream of measurements.
 */
@Service
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
    public void rename(String deviceId, String sensorId, String name) {
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
     * @throws IllegalArgumentException if the values are partially filled or out
     *         of order
     */
    @Transactional
    public void setThresholds(String deviceId, String sensorId, SensorThresholds thresholds) {
        // Validated here rather than only in the form, so the rule holds
        // regardless of who calls.
        thresholds.validate();
        SensorSettings settings = load(deviceId, sensorId);
        settings.setThresholds(thresholds);
        repository.save(settings);
    }

    private SensorSettings load(String deviceId, String sensorId) {
        return repository.findByDeviceIdAndSensorId(deviceId, sensorId)
                .orElseGet(() -> new SensorSettings(deviceId, sensorId));
    }
}
