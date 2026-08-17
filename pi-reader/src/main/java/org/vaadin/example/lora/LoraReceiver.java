package org.vaadin.example.lora;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.drivers.radio.lora.lr11xx.Lr1121Driver;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalOutputConfigBuilder;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.spi.Spi;
import com.pi4j.io.spi.SpiChipSelect;
import com.pi4j.io.spi.SpiConfigBuilder;
import com.pi4j.io.spi.SpiMode;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import org.vaadin.example.upstream.ScrewCloudSender;

/**
 * Listens for measurement packets over LoRa and passes them on to the server.
 *
 * <p>A relay, not a reader: the bytes that arrive over the air are the bytes
 * that leave in the datagram. A device out of WiFi range therefore appears on
 * the server as itself — its own identifier, its own sensors, its own sequence
 * numbers — rather than as readings attributed to this machine. Nothing on the
 * server had to be taught anything for that to work.
 *
 * <p>It is also why this does not decode. Re-encoding a packet is a way to
 * introduce a difference between what was sent and what arrives, and the server
 * already refuses what it cannot read.
 *
 * <p>Like the Bluetooth scanner, it degrades to a warning: a Pi with no radio
 * wired to it still runs everything else.
 */
@ApplicationScoped
public class LoraReceiver {

    private static final Logger LOG = Logger.getLogger(LoraReceiver.class);

    /** How long each listen lasts before the loop looks at whether to stop. */
    private static final Duration LISTEN_SLICE = Duration.ofSeconds(10);

    /** How many arrivals are kept for the page. Enough to see a pattern. */
    private static final int REMEMBERED = 10;

    @Inject
    ScrewCloudSender sender;

    @ConfigProperty(name = "screwcloud.lora.enabled", defaultValue = "false")
    boolean enabled;

    /*
       The defaults are the parameters of the link that has actually been made to
       work between a Core1121 on a Pico and the Core1121 on this machine — the
       ones in lora-node.ino, which are in turn Waveshare's own example's. They
       are not the best parameters; SF9 reaches considerably further. They are the
       proven ones, which is what a default is for when the failure mode is
       silence.

       There is no negotiation in LoRa and no error when two ends disagree. A
       mismatched spreading factor, a mismatched CRC setting and a missing antenna
       all look exactly alike, so any change here has to be made at the sender in
       the same breath — LORA_SPREADING_FACTOR and LORA_CRC in the firmware's
       config.h.
    */
    @ConfigProperty(name = "screwcloud.lora.frequency", defaultValue = "868000000")
    long frequencyHz;

    @ConfigProperty(name = "screwcloud.lora.spreading-factor", defaultValue = "7")
    int spreadingFactor;

    /**
     * Whether the radio appends and checks a CRC.
     *
     * <p>Off, because that is what the working link does and what Waveshare's
     * example does. Worth turning on at both ends once a link is up: a relay that
     * cannot tell a corrupt packet from a good one forwards the corruption, and
     * the server can only judge what it is given.
     */
    @ConfigProperty(name = "screwcloud.lora.crc", defaultValue = "false")
    boolean crc;

    private volatile String status = "Not started";
    private volatile boolean running = true;
    private volatile boolean listening = false;

    private final Deque<Arrival> arrivals = new ArrayDeque<>();

    /** A packet, and when it turned up. */
    public record Arrival(LoraPacket packet, Instant at) {
    }

    /** For the container, which fills in the configuration. */
    public LoraReceiver() {
    }

    /**
     * A receiver that is already listening and has heard these — which is what a
     * test of the page wants, the alternative being a radio. With no packets it
     * is a radio that is listening and has heard nothing, which the page has
     * something of its own to say about.
     */
    public static LoraReceiver alreadyListening(LoraPacket... heard) {
        LoraReceiver receiver = new LoraReceiver();
        receiver.listening = true;
        receiver.status = "Listening";
        for (LoraPacket packet : heard) {
            receiver.remember(packet);
        }
        return receiver;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            status = "Switched off";
            return;
        }
        Thread thread = new Thread(this::listen, "lora-receiver");
        thread.setDaemon(true);
        thread.start();
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
    }

    public String status() {
        return status;
    }

    /**
     * Whether the radio is actually listening, as opposed to switched off or
     * unavailable. The page uses it to tell silence from not trying.
     */
    public boolean listening() {
        return listening;
    }

    /** The most recent arrivals, newest first, for the page. */
    public synchronized List<Arrival> recent() {
        return List.copyOf(arrivals).reversed();
    }

    public synchronized Optional<Arrival> latest() {
        return Optional.ofNullable(arrivals.peekLast());
    }

    private void listen() {
        /*
           Built field by field rather than from defaults(), because the CRC has
           to be settable and that is the one field with no wither. The rest are
           the defaults' own values, which the sender also uses: 125 kHz, 4/5, the
           private sync word, an 8 symbol preamble, an explicit header — the
           length travels in the packet — and no IQ inversion.
        */
        Lr1121Driver.LoraSettings settings = new Lr1121Driver.LoraSettings(
                spreadingFactor, Lr1121Driver.LoraSettings.BW_125_KHZ,
                Lr1121Driver.LoraSettings.CR_4_5, Lr1121Driver.LoraSettings.SYNC_WORD_PRIVATE,
                8, true, crc, false);

        Context pi4j = Pi4J.newAutoContext();
        try (Lr1121Driver radio = new Lr1121Driver(spi(pi4j), resetLine(pi4j),
                inputLine(pi4j, "lora-busy", BUSY_PIN), inputLine(pi4j, "lora-irq", IRQ_PIN))) {
            Lr1121Driver.Version version = radio.version();
            LOG.infof("LoRa radio: %s", version);

            radio.configure(Lr1121Driver.BoardConfig.core1121());
            radio.configureLora(frequencyHz, settings);

            status = "Listening on %.3f MHz, SF%d, CRC %s".formatted(
                    frequencyHz / 1e6, spreadingFactor, crc ? "on" : "off");
            LOG.info(status);
            listening = true;

            while (running) {
                radio.receive(settings, LISTEN_SLICE).ifPresent(this::relay);
            }
        } catch (Exception e) {
            /*
               A Pi with no radio wired to it is an ordinary Pi. The Bluetooth
               reader and the local page do not depend on this in any way, and
               taking them down because a second radio is absent would be a poor
               trade.
            */
            status = "Unavailable: " + e.getMessage();
            LOG.warnf("The LoRa radio is not available (%s). Everything else still works.",
                    e.toString());
        } finally {
            listening = false;
            pi4j.shutdown();
        }
    }

    /*
       The wiring, which is this machine's business rather than the driver's — the
       driver takes the bus and the three lines and knows nothing about which pins
       they came from. These are Waveshare's own numbers for the Core1121.
    */
    private static final int RESET_PIN = 22;
    private static final int BUSY_PIN = 24;
    private static final int IRQ_PIN = 23;

    /**
     * 2 MHz rather than the 10 MHz the vendor's demo uses. Ten is fine down a short
     * ribbon to a HAT; over jumper wires with no ground return beside each signal it
     * is marginal, and marginal SPI does not fail cleanly — it corrupts the odd byte
     * into a plausible number, such as a version that reads 0x14 instead of 0x22.
     */
    private static Spi spi(Context pi4j) {
        return pi4j.create(SpiConfigBuilder.newInstance(pi4j)
                .id("lora-spi")
                .bus(0)
                .chipSelect(SpiChipSelect.CS_0)
                .mode(SpiMode.MODE_0)
                .baud(2_000_000)
                .build());
    }

    /**
     * High from the moment the line is claimed. Reset is active low, so an output
     * that comes up at zero holds the radio in reset — after which it answers
     * nothing, its busy line never falls, and every symptom points at the wiring.
     */
    private static DigitalOutput resetLine(Context pi4j) {
        return pi4j.create(DigitalOutputConfigBuilder.newInstance(pi4j)
                .id("lora-reset")
                .bcm(RESET_PIN)
                .initial(DigitalState.HIGH)
                .shutdown(DigitalState.HIGH)
                .build());
    }

    /**
     * Debounce off, explicitly. Pi4J defaults it to 10 000 microseconds, which is a
     * sensible guard for a pushbutton and ten milliseconds of damage here: these
     * lines carry a radio announcing an arrived packet, and two packets inside one
     * debounce window would be delivered as one.
     */
    private static DigitalInput inputLine(Context pi4j, String id, int pin) {
        return pi4j.create(DigitalInputConfigBuilder.newInstance(pi4j)
                .id(id)
                .bcm(pin)
                .debounce(0L)
                .build());
    }

    private void relay(Lr1121Driver.ReceivedPacket received) {
        LoraPacket packet = new LoraPacket(received.payload(), received.rssiDbm(), received.snrDb());
        remember(packet);

        LOG.infof("Relaying %s", packet.describe());
        sender.forward(packet.bytes());
    }

    private synchronized void remember(LoraPacket packet) {
        arrivals.addLast(new Arrival(packet, Instant.now()));
        while (arrivals.size() > REMEMBERED) {
            arrivals.removeFirst();
        }
    }
}
