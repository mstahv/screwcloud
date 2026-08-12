package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import fi.mstahv.sensorhub.validation.DeviceId;

/**
 * Per-browser device lists.
 *
 * <p>{@code @Validated} makes the constraints on the parameters below run before
 * the method does, so the rules hold no matter who calls — the form is where a
 * reader is told about them, not where they are enforced. A violation is a
 * {@link jakarta.validation.ConstraintViolationException}.
 */
@Service
@Validated
public class ClientDeviceStore {

    private final ClientDeviceRepository repository;

    ClientDeviceStore(ClientDeviceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<String> devicesFor(String clientId) {
        return repository.findByClientIdOrderByDeviceIdAsc(clientId).stream()
                .map(ClientDevice::getDeviceId)
                .toList();
    }

    /**
     * Adds a device to a browser's list. Adding the same device twice is a
     * no-op.
     *
     * @return the normalised device identifier
     * @throws jakarta.validation.ConstraintViolationException if the identifier is
     *         missing or not a device identifier
     */
    @Transactional
    public String add(@NotBlank @Size(max = 64) String clientId,
                      @NotBlank(message = "Give the device identifier")
                      @DeviceId String deviceId) {
        String normalised = normalise(deviceId);
        if (!repository.existsByClientIdAndDeviceId(clientId, normalised)) {
            repository.save(new ClientDevice(clientId, normalised, Instant.now()));
        }
        return normalised;
    }

    /**
     * Whether this browser has asked to be told when the device goes quiet.
     */
    @Transactional(readOnly = true)
    public boolean isSilenceAlertEnabled(String clientId, String deviceId) {
        return repository.findByClientIdAndDeviceId(clientId, normalise(deviceId))
                .map(ClientDevice::isAlertOnSilence)
                .orElse(false);
    }

    /**
     * Subscribes or unsubscribes this browser from silence alerts for one device.
     * Does nothing for a device that is not on the browser's list — there would be
     * nothing to attach the choice to.
     */
    @Transactional
    public void setSilenceAlert(@NotBlank String clientId, @NotBlank @DeviceId String deviceId,
                                boolean enabled) {
        repository.findByClientIdAndDeviceId(clientId, normalise(deviceId))
                .ifPresent(device -> {
                    device.setAlertOnSilence(enabled);
                    repository.save(device);
                });
    }

    /**
     * Every device someone is watching, and who is watching it.
     *
     * <p>The sweep that looks for silent devices starts from this rather than from
     * every device that ever sent a packet: a device nobody subscribed to needs no
     * checking, and one that was decommissioned months ago should not be
     * rediscovered as "offline" on every server start.
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> clientsWatchingForSilence() {
        return repository.findByAlertOnSilenceTrue().stream()
                .collect(Collectors.groupingBy(ClientDevice::getDeviceId,
                        Collectors.mapping(ClientDevice::getClientId, Collectors.toList())));
    }

    /**
     * Every browser that has this device on its list.
     *
     * <p>The audience for anything that is a fact about the device rather than a
     * personal setting — a degree-day counter reaching its target, for instance.
     * Whether a notification actually arrives still depends on that browser having
     * notifications switched on.
     */
    @Transactional(readOnly = true)
    public List<String> clientsWith(String deviceId) {
        return repository.findByDeviceId(normalise(deviceId)).stream()
                .map(ClientDevice::getClientId)
                .distinct()
                .toList();
    }

    @Transactional
    public void remove(@NotBlank String clientId, @NotBlank @DeviceId String deviceId) {
        repository.deleteByClientIdAndDeviceId(clientId, normalise(deviceId));
    }

    /*
       The device sends the identifier in upper case and the decoder trims the
       space padding, so user input is normalised the same way. Otherwise "laht"
       and "LAHT" would be different devices.

       Nothing is rejected here any more: @DeviceId has already had its say on
       every method that takes an identifier from outside, and it deliberately
       judges the value as this leaves it — stripped, and regardless of case.
       The queries below tolerate a null the same way they tolerate an unknown
       identifier, by finding nothing.
    */
    private static String normalise(String deviceId) {
        return deviceId == null ? "" : deviceId.strip().toUpperCase(Locale.ROOT);
    }
}
