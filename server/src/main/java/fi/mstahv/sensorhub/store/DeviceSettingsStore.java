package fi.mstahv.sensorhub.store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.DeviceId;

/**
 * User settings for devices: the display name.
 *
 * <p>The identifier is what fits a UDP packet and a {@code config.h}; the name is
 * what somebody looking for their cabin actually reads. It is a fact about the
 * device rather than a per-browser preference — the same reasoning as sensor
 * names — so everyone watching the device sees the same one.
 *
 * <p>The rules on what may be stored are the constraints on the parameters;
 * {@code @Validated} runs them before the method body.
 */
@Service
@Validated
public class DeviceSettingsStore {

    private final DeviceSettingsRepository repository;

    DeviceSettingsStore(DeviceSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the device's name, or null if none has been given
     */
    @Transactional(readOnly = true)
    public String nameFor(String deviceId) {
        return repository.findByDeviceId(deviceId)
                .map(DeviceSettings::getName)
                .filter(name -> !name.isBlank())
                .orElse(null);
    }

    /**
     * What the UI shows for the device wherever it is mentioned: the name, or
     * the identifier while there is none.
     */
    @Transactional(readOnly = true)
    public String displayNameFor(String deviceId) {
        String name = nameFor(deviceId);
        return name != null ? name : deviceId;
    }

    /**
     * Sets the name. An empty or whitespace-only value clears it, after which
     * the UI shows the device identifier again.
     */
    @Transactional
    public void rename(@NotBlank @DeviceId String deviceId, @Size(max = 64) String name) {
        DeviceSettings settings = load(deviceId);
        settings.setName(cleaned(name));
        repository.save(settings);
    }

    /**
     * @return the token of the building drawing chosen for the device, or null
     *         for none. What the tokens mean is the UI's business — an unknown
     *         one renders as no icon rather than being an error here.
     */
    @Transactional(readOnly = true)
    public String iconFor(String deviceId) {
        return repository.findByDeviceId(deviceId)
                .map(DeviceSettings::getIcon)
                .filter(icon -> !icon.isBlank())
                .orElse(null);
    }

    /** Sets the icon token. An empty or whitespace-only value clears it. */
    @Transactional
    public void setIcon(@NotBlank @DeviceId String deviceId, @Size(max = 16) String icon) {
        DeviceSettings settings = load(deviceId);
        settings.setIcon(cleaned(icon));
        repository.save(settings);
    }

    private DeviceSettings load(String deviceId) {
        return repository.findByDeviceId(deviceId)
                .orElseGet(() -> new DeviceSettings(deviceId));
    }

    private static String cleaned(String value) {
        String stripped = value == null ? null : value.strip();
        return stripped == null || stripped.isEmpty() ? null : stripped;
    }
}
