package org.vaadin.example.ruuvi;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.DiscoveryFilter;
import com.github.hypfvieh.bluetooth.DiscoveryTransport;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;

import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.Variant;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import org.vaadin.example.history.ReadingHistory;

/**
 * Listens for RuuviTag advertisements through BlueZ and feeds them to the
 * {@link TagRegistry}.
 *
 * <p>Nothing is ever connected to. A tag broadcasts its whole measurement about
 * every 1.3 seconds and this only has to be listening, which is also why a tag
 * appears the moment it is switched on with nothing to configure.
 *
 * <p>BlueZ keeps a device object per address and updates its {@code ManufacturerData}
 * property as advertisements arrive, so the work here is to start a discovery and
 * read that property. The catch is that the property <em>stays</em> at the last
 * advertisement after a tag stops transmitting, so a reading polled twice would
 * otherwise look like two readings and a dead tag would look alive forever. Data
 * Format 5 carries a measurement sequence number for exactly this: a payload whose
 * sequence has not moved is the same broadcast, not a new one.
 *
 * <p>The whole thing degrades to a warning. A Pi with no Bluetooth, a developer's
 * laptop with no BlueZ, a D-Bus that will not answer — none of those should stop
 * the local display from starting, and the status is readable in the UI.
 */
@ApplicationScoped
public class BleScanner {

    private static final Logger LOG = Logger.getLogger(BleScanner.class);

    @Inject
    TagRegistry registry;

    @Inject
    ReadingHistory history;

    @ConfigProperty(name = "screwcloud.ble.enabled", defaultValue = "true")
    boolean enabled;

    /**
     * How often BlueZ is asked what it has heard. A tag broadcasts every 1.3
     * seconds, so anything at or below that pace misses nothing that the sequence
     * number would not catch anyway.
     */
    @ConfigProperty(name = "screwcloud.ble.poll-interval", defaultValue = "PT2S")
    Duration pollInterval;

    /** What the UI says about the radio, in words a person can act on. */
    private volatile String status = "Not started";

    private volatile boolean running = true;

    /** The last sequence number seen per address; see the class comment. */
    private final Map<String, Integer> lastSequence = new HashMap<>();

    /** Whether anything has ever been decoded, which decides how loud this is. */
    private volatile boolean reported;

    private long polls;
    private long failures;
    private long refusals;
    private volatile boolean described;

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            status = "Bluetooth scanning is switched off";
            LOG.info(status);
            return;
        }
        Thread thread = new Thread(this::scan, "ruuvi-ble-scanner");
        thread.setDaemon(true);
        thread.start();
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
    }

    public String status() {
        return status;
    }

    private void scan() {
        DeviceManager manager;
        BluetoothAdapter adapter;
        try {
            manager = DeviceManager.createInstance(false);
            adapter = manager.getAdapter();
            if (adapter == null) {
                status = "No Bluetooth adapter";
                LOG.warn(status + " — the local display works, but there is nothing to show on it");
                return;
            }
            if (!adapter.isPowered()) {
                adapter.setPowered(true);
                LOG.info("Powered on the Bluetooth adapter");
            }
            /*
               Low energy only, and DuplicateData so that BlueZ passes on repeated
               advertisements instead of only the first from each tag.
            */
            manager.setScanFilter(Map.of(
                    DiscoveryFilter.Transport, DiscoveryTransport.LE,
                    DiscoveryFilter.DuplicateData, true));

            /*
               The return value matters. A discovery already running — another
               process, or bluetoothctl left open — is refused here, and without
               this the scanner would poll a BlueZ that was never asked to listen
               and report nothing, for hours, in silence.
            */
            if (!adapter.startDiscovery()) {
                status = "Could not start scanning: BlueZ refused the discovery";
                LOG.warn(status + ". Something else may already be scanning, or this user"
                        + " may not be allowed to — check with:  id -nG  and  busctl --system"
                        + " get-property org.bluez /org/bluez/hci0 org.bluez.Adapter1 Discovering");
            } else {
                status = "Listening on %s".formatted(adapter.getAddress());
                LOG.infof("Scanning for RuuviTags on adapter %s (%s)",
                        adapter.getName(), adapter.getAddress());
            }
        } catch (Exception e) {
            /*
               Every failure here is an environment that has no Bluetooth to offer:
               no D-Bus socket, no BlueZ, no permission. None of them is worth a
               stack trace on a Pi, and none of them should take the UI down.
            */
            status = "Bluetooth unavailable: " + e.getMessage();
            LOG.warnf("Bluetooth is not available (%s). The local display still works.",
                    e.toString());
            return;
        }

        while (running) {
            try {
                ensureDiscovering(adapter);
                collect(manager);
            } catch (Exception e) {
                /*
                   Loudly the first time, quietly after that. A failure repeating
                   every two seconds must not fill a log, but a scanner that finds
                   nothing and says nothing is the worst of both worlds — and this
                   is the branch a wrong assumption about the D-Bus types lands in.
                */
                if (failures++ == 0) {
                    LOG.warnf(e, "Reading advertisements failed; will keep trying "
                            + "(further failures are logged at debug level)");
                } else {
                    LOG.debugf(e, "Reading advertisements failed again (%d)", failures);
                }
            }
            sleep(pollInterval);
        }
    }

    /**
     * BlueZ does not always keep a discovery running for weeks. It stops on a
     * resume from suspend, when the adapter is reset, and when another client's
     * session ends — and from the outside that is indistinguishable from a house
     * where every tag has gone quiet at once. Cheap to check, so it is checked.
     */
    private void ensureDiscovering(BluetoothAdapter adapter) {
        if (adapter.isDiscovering()) {
            refusals = 0;
            return;
        }
        if (adapter.startDiscovery()) {
            refusals = 0;
            status = "Listening on %s".formatted(adapter.getAddress());
            LOG.info("Discovery had stopped; started it again");
        } else {
            status = "Not scanning: BlueZ will not start a discovery";
            // Once, not every two seconds for as long as the machine is up.
            if (refusals++ == 0) {
                LOG.warn(status + " — will keep trying quietly");
            }
        }
    }

    private void collect(DeviceManager manager) {
        Instant now = Instant.now();
        // true: read what BlueZ already has rather than starting a scan of its own.
        List<BluetoothDevice> devices = manager.getDevices(true);
        Set<String> companies = new TreeSet<>();
        int ruuviSeen = 0;

        for (BluetoothDevice device : devices) {
            Optional<byte[]> payload = payloadOf(device, companies);
            if (payload.isEmpty()) {
                continue;
            }
            ruuviSeen++;
            Optional<RuuviReading> reading = DataFormat5.parse(payload.get(), now, device.getRssi());
            if (reading.isEmpty()) {
                LOG.debugf("%s sent Ruuvi data in a format this does not decode",
                        device.getAddress());
                continue;
            }
            if (isNewBroadcast(reading.get())) {
                registry.store(reading.get());
                history.add(reading.get());
                if (!reported) {
                    reported = true;
                    LOG.infof("First reading: %s %s %.2f C", reading.get().sensorId(),
                            reading.get().macAddress(), reading.get().temperature());
                }
            }
        }

        report(devices.size(), ruuviSeen, companies);
    }

    /**
     * Says what the radio is actually seeing, because "nothing on the screen" has
     * several causes that look identical from the outside: BlueZ hearing nothing,
     * BlueZ hearing plenty but none of it Ruuvi, or this code failing to read what
     * it was given.
     *
     * <p>Once a minute at most, and only while nothing has been decoded — after the
     * first reading the page itself is the report.
     */
    private void report(int devices, int ruuviSeen, Set<String> companies) {
        if (reported || polls++ % Math.max(1, 60_000 / Math.max(1, (int) pollInterval.toMillis())) != 0) {
            return;
        }
        if (devices == 0) {
            LOG.warn("BlueZ reports no devices at all. Is anything advertising in range?"
                    + " Check with: bluetoothctl --timeout 10 scan le");
        } else if (ruuviSeen == 0) {
            LOG.warnf("Heard %d device(s), none of them a RuuviTag. Manufacturer ids seen: %s"
                    + " (a RuuviTag advertises under 0x0499)", devices,
                    companies.isEmpty() ? "none — no device exposed manufacturer data" : companies);
        } else {
            LOG.warnf("Heard %d device(s) and %d Ruuvi advertisement(s), but none decoded",
                    devices, ruuviSeen);
        }
    }

    /**
     * Ruuvi's slice of the advertisement, if this device sent one.
     *
     * <p>The map is declared as holding byte arrays, but D-Bus types a dictionary
     * of variants, so both the key and the value may arrive either unwrapped or
     * still boxed depending on the transport. All of it is read defensively and
     * every manufacturer id seen is collected, so a mismatch shows up in the log as
     * "here is what was there" rather than as silence.
     */
    private Optional<byte[]> payloadOf(BluetoothDevice device, Set<String> companies) {
        Map<?, ?> manufacturerData = device.getManufacturerData();
        if (manufacturerData == null) {
            return Optional.empty();
        }
        Optional<byte[]> ruuvi = Optional.empty();
        for (Map.Entry<?, ?> entry : manufacturerData.entrySet()) {
            int company = companyOf(entry.getKey());
            companies.add("0x%04X".formatted(company));
            if (company == DataFormat5.COMPANY_ID) {
                Optional<byte[]> bytes = toBytes(entry.getValue());
                if (bytes.isPresent()) {
                    ruuvi = bytes;
                } else {
                    describeOnce(entry.getValue());
                }
            }
        }
        return ruuvi;
    }

    private static int companyOf(Object key) {
        Object unwrapped = unwrap(key);
        if (unwrapped instanceof UInt16 id) {
            return id.intValue();
        }
        return unwrapped instanceof Number number ? number.intValue() : -1;
    }

    /**
     * The advertisement's bytes, whatever shape D-Bus chose to hand them over in.
     *
     * <p>The signature is {@code ay} — an array of bytes — but what arrives on the
     * Java side depends on the transport and the marshalling: a {@code byte[]}, the
     * boxed {@code Byte[]}, or a list of numbers, any of them possibly still inside
     * a {@link Variant}. Guessing one of those and being wrong is invisible: the
     * scanner then hears a tag, recognises Ruuvi's company id, and reports that it
     * heard nothing.
     */
    static Optional<byte[]> toBytes(Object value) {
        Object unwrapped = unwrap(value);
        if (unwrapped instanceof byte[] bytes) {
            return Optional.of(bytes);
        }
        if (unwrapped instanceof Byte[] boxed) {
            byte[] bytes = new byte[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                bytes[i] = boxed[i];
            }
            return Optional.of(bytes);
        }
        if (unwrapped instanceof Collection<?> items) {
            byte[] bytes = new byte[items.size()];
            int i = 0;
            for (Object item : items) {
                Object element = unwrap(item);
                if (!(element instanceof Number number)) {
                    return Optional.empty();
                }
                bytes[i++] = number.byteValue();
            }
            return Optional.of(bytes);
        }
        return Optional.empty();
    }

    /** Variants can nest, so this unwraps until there is nothing left to unwrap. */
    private static Object unwrap(Object value) {
        Object unwrapped = value;
        while (unwrapped instanceof Variant<?> variant) {
            unwrapped = variant.getValue();
        }
        return unwrapped;
    }

    /**
     * Names the type once, when the bytes arrive as something none of the above
     * recognises. Whoever reads this log should not have to attach a debugger to
     * find out what BlueZ actually sent.
     */
    private void describeOnce(Object value) {
        if (described) {
            return;
        }
        described = true;
        Object unwrapped = unwrap(value);
        String detail = unwrapped == null ? "null" : unwrapped.getClass().getName();
        if (unwrapped instanceof Collection<?> items && !items.isEmpty()) {
            detail += " of " + items.iterator().next().getClass().getName();
        }
        LOG.warnf("A RuuviTag was heard but its advertisement arrived as %s, which this"
                + " does not know how to read as bytes. Please report this type.", detail);
    }

    /**
     * Whether this is a broadcast that has not been counted yet. BlueZ hands out
     * the last advertisement over and over; the tag's own sequence number is what
     * separates a new measurement from the same one read again.
     */
    private synchronized boolean isNewBroadcast(RuuviReading reading) {
        Integer previous = lastSequence.put(reading.macAddress(), reading.sequenceNumber());
        return previous == null || previous != reading.sequenceNumber();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
