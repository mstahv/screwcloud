package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-browser device lists.
 */
@Service
public class ClientDeviceStore {

    /** A device identifier is 4 ASCII characters in the protocol. */
    public static final int MAX_DEVICE_ID_LENGTH = 4;

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
     * @throws IllegalArgumentException if the identifier is empty or too long
     */
    @Transactional
    public String add(String clientId, String deviceId) {
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
    public void setSilenceAlert(String clientId, String deviceId, boolean enabled) {
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

    @Transactional
    public void remove(String clientId, String deviceId) {
        repository.deleteByClientIdAndDeviceId(clientId, normalise(deviceId));
    }

    /*
       The device sends the identifier in upper case and the decoder trims the
       space padding, so user input is normalised the same way. Otherwise "laht"
       and "LAHT" would be different devices.
    */
    private static String normalise(String deviceId) {
        String cleaned = deviceId == null ? "" : deviceId.strip().toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Device identifier is missing");
        }
        if (cleaned.length() > MAX_DEVICE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "A device identifier is at most " + MAX_DEVICE_ID_LENGTH + " characters");
        }
        return cleaned;
    }
}
