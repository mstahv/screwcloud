package org.vaadin.example.upstream;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.vaadin.example.protocol.MeasurementPacket;
import org.vaadin.example.protocol.Protocol;
import org.vaadin.example.protocol.SensorReading;
import org.vaadin.example.ruuvi.RuuviReading;
import org.vaadin.example.ruuvi.TagRegistry;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Forwards what has been heard to the ScrewCloud server, in the same packet a
 * microcontroller sends.
 *
 * <p>UDP and fire and forget, exactly like the firmware: there is no reply to wait
 * for, and a packet lost on the way is replaced by the next one five minutes
 * later. The consequence worth stating is that a send reported as successful only
 * means the datagram left this machine.
 *
 * <p>The upload is not what keeps the display working. Everything the local view
 * shows comes from the registry, so an internet connection that is down costs the
 * history on the server and nothing else — which is the reason this application
 * exists in the first place.
 */
@ApplicationScoped
public class ScrewCloudSender {

    private static final Logger LOG = Logger.getLogger(ScrewCloudSender.class);

    @Inject
    TagRegistry registry;

    @ConfigProperty(name = "screwcloud.upload.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "screwcloud.upload.host")
    String host;

    @ConfigProperty(name = "screwcloud.upload.port", defaultValue = "5555")
    int port;

    /** This reader's identifier, the same 4 characters a device uses. */
    @ConfigProperty(name = "screwcloud.device-id")
    String deviceId;

    /**
     * A tag heard longer ago than this is left out of the packet. The firmware uses
     * a minute for the same purpose; a tag broadcasts every 1.3 seconds, so silence
     * for a minute means out of range or a flat battery, not a slow tag.
     */
    @ConfigProperty(name = "screwcloud.upload.stale-after", defaultValue = "PT1M")
    Duration staleAfter;

    /** For the container, which fills in the configuration. */
    public ScrewCloudSender() {
    }

    /** With the identifier given outright, which is what a test wants. */
    public ScrewCloudSender(String deviceId) {
        this.deviceId = deviceId;
    }

    private int sequence;
    private volatile String status = "Nothing sent yet";
    private volatile Instant lastSuccess;

    /**
     * Every five minutes, matching the firmware's interval — the server's
     * "device has gone quiet" alert learns the pace a device keeps, so a reader
     * that sent at some other rate would just teach it a different one, and the two
     * would report at different resolutions for no reason.
     */
    @Scheduled(every = "{screwcloud.upload.interval}", delayed = "30s")
    void send() {
        if (!enabled) {
            return;
        }
        List<RuuviReading> fresh = registry.heardWithin(staleAfter, Instant.now());
        if (fresh.isEmpty()) {
            status = "Nothing to send: no tag heard in the last " + staleAfter;
            LOG.debug(status);
            return;
        }

        List<RuuviReading> included = fresh;
        if (included.size() > Protocol.MAX_SENSORS) {
            /*
               The packet holds eight. Sending the eight heard most recently is the
               least surprising choice, and unlike the firmware — which drops the
               overflow with no way to say so — this says which ones were left out.
            */
            included = fresh.subList(0, Protocol.MAX_SENSORS);
            LOG.warnf("Heard %d tags but a packet holds %d; leaving out %s",
                    fresh.size(), Protocol.MAX_SENSORS,
                    fresh.subList(Protocol.MAX_SENSORS, fresh.size()).stream()
                            .map(RuuviReading::sensorId).toList());
        }

        List<String> clashing = registry.duplicateSensorIds();
        if (!clashing.isEmpty()) {
            LOG.warnf("Two tags share the identifier(s) %s; the server will see them as one",
                    clashing);
        }

        byte[] packet = MeasurementPacket.encode(deviceId, sequence++, included.stream()
                .map(reading -> new SensorReading(
                        reading.sensorId(), reading.temperature(), reading.humidity()))
                .toList());

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(new DatagramPacket(packet, packet.length,
                    InetAddress.getByName(host), port));
            lastSuccess = Instant.now();
            status = "Sent %d sensor(s) to %s:%d".formatted(included.size(), host, port);
            LOG.debug(status);
        } catch (IOException e) {
            status = "Send failed: " + e.getMessage();
            LOG.warnf("Could not send to %s:%d (%s)", host, port, e.getMessage());
        }
    }

    /** What the local view says about the upload. */
    public String status() {
        return status;
    }

    /**
     * The identifier this reader's packets carry. Worth showing on the page: it is
     * the name the server files these readings under, and the one thing here that
     * cannot be worked out by looking at the tags.
     */
    public String deviceId() {
        return deviceId;
    }

    public Instant lastSuccess() {
        return lastSuccess;
    }
}
