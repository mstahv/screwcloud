package org.vaadin.example.thingy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattService;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import org.vaadin.example.history.ReadingHistory;
import org.vaadin.example.ruuvi.BleScanner;
import org.vaadin.example.ruuvi.TagRegistry;

/**
 * Reads the temperature and humidity of a Nordic Thingy:52.
 *
 * <p><strong>This one has to connect.</strong> That makes it the odd one out here,
 * and the reason is not a preference: a Thingy's environment characteristics are
 * <em>notify only</em> in Nordic's firmware — not readable, and not broadcast in
 * the advertisement either. There is no version of this that only listens, the way
 * the RuuviTag scanner does. So this connects once, subscribes, and from then on
 * BlueZ keeps the last notified value in the characteristic's {@code Value}
 * property, where polling it costs nothing and looks exactly like polling
 * {@code ManufacturerData} for a Ruuvi advertisement.
 *
 * <p>Consequences worth knowing, because none of them announce themselves:
 *
 * <ul>
 * <li><strong>One host at a time.</strong> A connected Thingy is not available to
 * the phone app. The nRF Thingy app taking it will disconnect this reader, which
 * recovers on the next poll — but the two cannot both have it.</li>
 * <li><strong>Connecting is slow and can fail</strong>, particularly while a
 * discovery is running, which one always is here because the Ruuvi scanner needs
 * it. That is why this has a thread of its own: a connect that blocks for seconds
 * must not stall the advertisement polling.</li>
 * <li><strong>The value is a cache.</strong> It stays at the last notification
 * after the Thingy goes away, exactly as {@code ManufacturerData} does. Staleness
 * is therefore judged by the connection: a reading is only recorded while the
 * device is still connected and notifying.</li>
 * </ul>
 *
 * <p>Off by default, and degrades to a warning like everything else that touches
 * a radio here. A house with no Thingy in it should notice nothing.
 */
@ApplicationScoped
public class ThingyReader {

    private static final Logger LOG = Logger.getLogger(ThingyReader.class);

    /**
     * Nordic's services all sit under {@code EF68xxxx-9B35-4933-9B10-52FFA9740042}.
     * The environment service is {@code 0200}, its temperature {@code 0201} and its
     * humidity {@code 0203}. BlueZ writes UUIDs in lower case, and so are these.
     */
    static final String ENVIRONMENT_SERVICE = "ef680200-9b35-4933-9b10-52ffa9740042";
    static final String TEMPERATURE_CHARACTERISTIC = "ef680201-9b35-4933-9b10-52ffa9740042";
    static final String HUMIDITY_CHARACTERISTIC = "ef680203-9b35-4933-9b10-52ffa9740042";

    /** What every Thingy service UUID begins with, which is how one is recognised. */
    private static final String NORDIC_PREFIX = "ef68";

    /** How long to wait for BlueZ to walk the GATT table after connecting. */
    private static final Duration SERVICE_RESOLUTION_TIMEOUT = Duration.ofSeconds(15);

    @Inject
    TagRegistry registry;

    @Inject
    ReadingHistory history;

    @Inject
    BleScanner scanner;

    @ConfigProperty(name = "screwcloud.thingy.enabled", defaultValue = "false")
    boolean enabled;

    /**
     * Which Thingy, when there is more than one in range. Empty means the first
     * one found, which is the ordinary case: there is one of them, in a corner.
     *
     * <p>{@code Optional}, and not for tidiness. An empty value in a properties
     * file is no value at all as far as the config layer is concerned, so a plain
     * {@code String} here does not fall back to the default — it fails to convert,
     * and takes the whole application down at startup with "Failed to load config
     * value of type class java.lang.String". {@code screwcloud.names.file} is
     * declared this way for the same reason.
     */
    @ConfigProperty(name = "screwcloud.thingy.address", defaultValue = "")
    Optional<String> address;

    /**
     * How often the notified value is picked up. Nordic's firmware notifies every
     * two seconds by default and this is not asking the device for anything — it
     * reads a property BlueZ already holds — so the pace here costs nothing.
     */
    @ConfigProperty(name = "screwcloud.thingy.poll-interval", defaultValue = "PT5S")
    Duration pollInterval;

    private volatile String status = "Not started";
    private volatile boolean running = true;

    /** So that a Thingy that is simply not switched on is not shouted about. */
    private boolean complainedAboutMissing;
    private boolean reported;

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            status = "Switched off";
            return;
        }
        Thread thread = new Thread(this::read, "thingy-reader");
        thread.setDaemon(true);
        thread.start();
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
    }

    /** What the local view says about the Thingy. */
    public String status() {
        return status;
    }

    private void read() {
        while (running) {
            try {
                poll();
            } catch (Exception e) {
                /*
                   Anything BlueZ throws is a device that went away mid-poll: it
                   disconnected, the phone app took it, or it was switched off. The
                   next pass reconnects, so this is a debug line rather than a
                   warning that would repeat every five seconds forever.
                */
                status = "Unavailable: " + e.getMessage();
                LOG.debugf(e, "Reading the Thingy failed; will try again");
            }
            sleep(pollInterval);
        }
    }

    private void poll() {
        Optional<DeviceManager> manager = scanner.deviceManager();
        if (manager.isEmpty()) {
            status = "Waiting for Bluetooth";
            return;
        }

        Optional<BluetoothDevice> found = find(manager.get());
        if (found.isEmpty()) {
            status = "No Thingy found";
            if (!complainedAboutMissing) {
                complainedAboutMissing = true;
                LOG.infof("No Thingy:52 in range yet%s. It advertises only while awake —"
                        + " press its button if it has gone to sleep.",
                        wantedAddress().map(" at "::concat).orElse(""));
            }
            return;
        }
        complainedAboutMissing = false;

        BluetoothDevice device = found.get();
        if (!connect(device)) {
            return;
        }

        BluetoothGattService environment = device.getGattServiceByUuid(ENVIRONMENT_SERVICE);
        if (environment == null) {
            /*
               Connected, but the GATT table has no environment service. Either the
               services are not resolved yet — the next poll will have them — or
               this is a Nordic device that is not a Thingy at all.
            */
            status = "Connected, waiting for the environment service";
            return;
        }

        Optional<Double> temperature = subscribedValue(environment, TEMPERATURE_CHARACTERISTIC)
                .flatMap(ThingyReading::decodeTemperature);
        Optional<Double> humidity = subscribedValue(environment, HUMIDITY_CHARACTERISTIC)
                .flatMap(ThingyReading::decodeHumidity);

        if (temperature.isEmpty() && humidity.isEmpty()) {
            /*
               Subscribed but nothing has arrived yet. Nordic's default interval is
               a couple of seconds, so this is the state for one poll after
               connecting and then never again.
            */
            status = "Connected, waiting for the first notification";
            return;
        }

        ThingyReading reading = new ThingyReading(device.getAddress(),
                temperature.orElse(null), humidity.orElse(null),
                device.getRssi(), Instant.now());

        registry.store(reading);
        history.add(reading);

        status = "Connected to %s as %s".formatted(device.getAddress(), reading.sensorId());
        if (!reported) {
            reported = true;
            LOG.infof("First Thingy reading: %s %s %s C", reading.sensorId(),
                    reading.macAddress(), reading.temperature());
        }
    }

    /**
     * The Thingy, by address when one was configured and by what it looks like
     * otherwise: a device advertising one of Nordic's {@code EF68} services, or
     * simply calling itself Thingy.
     */
    private Optional<BluetoothDevice> find(DeviceManager manager) {
        // true: use what the running discovery has already found.
        List<BluetoothDevice> devices = manager.getDevices(true);

        Optional<String> wanted = wantedAddress();
        if (wanted.isPresent()) {
            return devices.stream()
                    .filter(device -> wanted.get().equalsIgnoreCase(device.getAddress()))
                    .findFirst();
        }
        return devices.stream().filter(ThingyReader::looksLikeAThingy).findFirst();
    }

    /**
     * The configured address, or empty when none was given. Blank counts as none,
     * so a property left as spaces behaves like a property left alone.
     */
    private Optional<String> wantedAddress() {
        return address.map(String::trim).filter(value -> !value.isEmpty());
    }

    private static boolean looksLikeAThingy(BluetoothDevice device) {
        String name = device.getName();
        if (name != null && name.toLowerCase().startsWith("thingy")) {
            return true;
        }
        String[] uuids = device.getUuids();
        if (uuids == null) {
            return false;
        }
        for (String uuid : uuids) {
            if (uuid != null && uuid.toLowerCase().startsWith(NORDIC_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Connects if it is not connected already, and waits for BlueZ to walk the
     * GATT table.
     *
     * <p>The wait is the part that is easy to leave out and expensive to leave
     * out: {@code connect()} returning true means a link, not a list of services,
     * and asking for a characteristic before {@code ServicesResolved} gives a null
     * that looks exactly like a device without that service.
     */
    private boolean connect(BluetoothDevice device) {
        if (!Boolean.TRUE.equals(device.isConnected())) {
            status = "Connecting to " + device.getAddress();
            if (!device.connect()) {
                status = "Could not connect to " + device.getAddress();
                LOG.debugf("Connecting to the Thingy at %s failed", device.getAddress());
                return false;
            }
            LOG.infof("Connected to the Thingy at %s", device.getAddress());
        }

        Instant deadline = Instant.now().plus(SERVICE_RESOLUTION_TIMEOUT);
        while (running && !Boolean.TRUE.equals(device.isServicesResolved())) {
            if (Instant.now().isAfter(deadline)) {
                status = "Connected, but BlueZ never resolved the services";
                return false;
            }
            sleep(Duration.ofMillis(200));
        }
        return running;
    }

    /**
     * The last notified value of a characteristic, subscribing first if this is
     * the first time round.
     *
     * <p>{@code getValue()} is BlueZ's cached copy rather than a read of the
     * device — these characteristics cannot be read at all — so it is empty until
     * the first notification arrives and then costs nothing to poll.
     */
    private Optional<byte[]> subscribedValue(BluetoothGattService service, String uuid) {
        BluetoothGattCharacteristic characteristic = service.getGattCharacteristicByUuid(uuid);
        if (characteristic == null) {
            return Optional.empty();
        }
        try {
            if (!Boolean.TRUE.equals(characteristic.isNotifying())) {
                characteristic.startNotify();
                LOG.debugf("Subscribed to %s", uuid);
                // Nothing has arrived yet; the next poll is the one with a value.
                return Optional.empty();
            }
        } catch (Exception e) {
            LOG.debugf(e, "Could not subscribe to %s", uuid);
            return Optional.empty();
        }
        byte[] value = characteristic.getValue();
        return value == null || value.length == 0 ? Optional.empty() : Optional.of(value);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
