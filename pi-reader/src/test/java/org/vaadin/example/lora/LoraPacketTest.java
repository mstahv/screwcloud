package org.vaadin.example.lora;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.vaadin.example.protocol.MeasurementPacket;
import org.vaadin.example.protocol.SensorReading;

/**
 * Reading a packet that arrived over the air.
 *
 * <p>The relay does not need this — the bytes go to the server untouched — but the
 * page does: a node out of WiFi range should show its temperature here rather than
 * only a line saying that sixteen bytes went past.
 *
 * <p>The packets are built with this project's own encoder, so a change to the wire
 * format breaks these tests too. That is the point: the decoder and the encoder are
 * two halves of one agreement, and they are in the same repository.
 */
class LoraPacketTest {

    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");

    @Test
    void aPacketFromTheSleeperBecomesAReading() {
        byte[] bytes = MeasurementPacket.encode("SLP1", 7,
                List.of(new SensorReading("CPU", 19.62, null)));

        List<RelayedReading> readings = new LoraPacket(bytes, -97, 7.5).readings(NOW);

        assertEquals(1, readings.size());
        RelayedReading cpu = readings.getFirst();
        assertEquals("SLP1", cpu.deviceId());
        assertEquals("CPU", cpu.packetSensorId(), "what the node itself calls it");
        assertEquals("LoRa SLP1", cpu.sensorId(),
                "on the page it says where it came from; CPU next to this machine's own"
                + " CPU would say nothing at all");
        assertEquals(19.62, cpu.temperature(), 0.001);
        assertNull(cpu.humidity(), "the sleeper sends no humidity, and a dash is not 0 %");
        assertEquals((short) -97, cpu.rssi(), "the strength it was heard at belongs to the reading");
    }

    @Test
    void everySensorInAPacketBecomesItsOwnReading() {
        byte[] bytes = MeasurementPacket.encode("HOME", 1, List.of(
                new SensorReading("R0BF", 21.4, 41.0),
                new SensorReading("R1AC", 4.2, 78.5),
                new SensorReading("CPU", 34.1, null)));

        List<RelayedReading> readings = new LoraPacket(bytes, -80, 9.0).readings(NOW);

        assertEquals(List.of("R0BF", "R1AC", "CPU"),
                readings.stream().map(RelayedReading::packetSensorId).toList());
        assertEquals(List.of("LoRa HOME/R0BF", "LoRa HOME/R1AC", "LoRa HOME/CPU"),
                readings.stream().map(RelayedReading::sensorId).toList(),
                "with several sensors the packet's own name is needed to tell them apart");
        assertEquals(78.5, readings.get(1).humidity(), 0.001);
    }

    /** Below zero has to survive the trip; a freezer is the point of half of this. */
    @Test
    void temperaturesBelowZeroDecodeAsThemselves() {
        byte[] bytes = MeasurementPacket.encode("SLP1", 1,
                List.of(new SensorReading("CPU", -18.25, null)));

        assertEquals(-18.25, new LoraPacket(bytes, -90, 5.0).readings(NOW).getFirst()
                .temperature(), 0.001);
    }

    /**
     * Two devices can use the same sensor name — {@code CPU} is the obvious one — so
     * the key has to carry both, or one would overwrite the other in the registry.
     */
    @Test
    void theKeyCarriesTheDeviceAsWellAsTheSensor() {
        RelayedReading sleeper =
                new RelayedReading("SLP1", "CPU", true, 19.6, null, (short) -97, NOW);
        RelayedReading reader =
                new RelayedReading("HOME", "CPU", true, 34.1, null, (short) -70, NOW);

        assertTrue(sleeper.macAddress().contains("SLP1"));
        assertTrue(sleeper.macAddress().startsWith("lora:"),
                "it is not an address, and a reader of the key should be able to tell");
        assertNotEquals(sleeper.macAddress(), reader.macAddress(),
                "two devices' CPU sensors are two measuring points");
        assertNotEquals(sleeper.sensorId(), reader.sensorId(),
                "and the label has to separate them too, because a name is remembered"
                + " under it — two nodes both called LoRa would share one");
    }

    /**
     * Somebody else's device on the same frequency is still relayed — the server
     * decides what it can read — but it is not put on this page as a temperature.
     */
    @Test
    void somethingThatIsNotOneOfOursDecodesToNothing() {
        assertEquals(List.of(), new LoraPacket("hello there".getBytes(), -100, 2.0).readings(NOW));
        assertEquals(List.of(), new LoraPacket(new byte[] {1, 2, 3}, -100, 2.0).readings(NOW));
    }
}
