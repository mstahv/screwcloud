package fi.mstahv.sensorhub.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import fi.mstahv.sensorhub.alerts.HeatSumAlerts;
import fi.mstahv.sensorhub.alerts.TemperatureAlerts;
import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.PacketDecoder;
import fi.mstahv.sensorhub.store.MeasurementStore;

/**
 * Listens on a UDP port and stores the contents of decoded packets.
 *
 * <p>A dedicated thread and a blocking {@link DatagramSocket#receive}, because
 * the traffic is a few packets per minute — nothing justifies anything more
 * elaborate.
 */
@Component
public class UdpReceiver implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UdpReceiver.class);

    /** Comfortably above the largest possible packet (8 + 8 x 8 = 72 bytes). */
    private static final int MAX_PACKET_SIZE = 512;

    /**
     * How much of a rejected packet is written to the log. Enough to recognise
     * what sent it — a firmware's header, an AT command, a stray HTTP request —
     * without turning a flood of junk into a flood of log.
     */
    private static final int PREVIEW_BYTES = 24;

    private final MeasurementStore store;
    private final TemperatureAlerts alerts;
    private final HeatSumAlerts heatSums;
    private final int port;

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread thread;

    UdpReceiver(MeasurementStore store, TemperatureAlerts alerts, HeatSumAlerts heatSums,
                @Value("${sensorhub.udp.port}") int port) {
        this.store = store;
        this.alerts = alerts;
        this.heatSums = heatSums;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            socket = new DatagramSocket(port);
        } catch (SocketException e) {
            throw new IllegalStateException("Opening UDP port " + port + " failed", e);
        }
        running = true;
        thread = new Thread(this::receiveLoop, "udp-receiver");
        thread.setDaemon(true);
        thread.start();
        log.info("Listening for measurements on UDP port {}", port);
    }

    @Override
    public void stop() {
        running = false;
        if (socket != null) {
            // Breaks the blocking receive().
            socket.close();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (running) {
                    log.warn("UDP receive failed", e);
                }
                continue;
            }
            try {
                DeviceMeasurement measurement =
                        PacketDecoder.decode(packet.getData(), packet.getLength(), Instant.now());
                store.store(measurement);
                log.info("Received from {}: {} sensors, sequence {}",
                        measurement.deviceId(), measurement.sensors().size(), measurement.sequence());
                /*
                   After storing, because the alert compares the reading with the
                   previous one from the database. Sending itself happens on
                   another thread, so a slow push service cannot delay the next
                   packet.
                */
                alerts.evaluate(measurement);
                heatSums.evaluate(measurement.deviceId());
            } catch (RuntimeException e) {
                /*
                   Any junk can arrive on the port, so this is not exceptional.
                   The first bytes go in the log because the junk is usually a
                   device saying something about itself: an "Unknown protocol
                   version 65" with "AT+CIPSEND=0,16..." beside it is a modem that
                   was fed a command as a payload, which is a different problem
                   from a stray packet off the internet, and the number alone does
                   not distinguish them.
                */
                log.warn("Invalid packet from {}: {} [{}]",
                        packet.getAddress(), e.getMessage(),
                        describe(packet.getData(), packet.getLength()));
            }
        }
    }

    /** The first bytes as hex, with the printable ones spelled out beside them. */
    static String describe(byte[] data, int length) {
        int shown = Math.min(length, PREVIEW_BYTES);
        StringBuilder hex = new StringBuilder(shown * 3);
        StringBuilder text = new StringBuilder(shown);
        for (int i = 0; i < shown; i++) {
            int value = data[i] & 0xFF;
            hex.append("%02X ".formatted(value));
            text.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
        }
        return "%d bytes: %s| %s%s".formatted(
                length, hex, text, length > shown ? "..." : "");
    }
}
