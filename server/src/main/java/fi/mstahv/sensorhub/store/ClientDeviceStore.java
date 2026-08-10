package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

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
