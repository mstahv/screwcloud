package org.vaadin.example.ruuvi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.vaadin.example.lora.RelayedReading;
import org.vaadin.example.sensor.Reading;
import org.vaadin.example.thingy.ThingyReading;

class TagRegistryTest {

    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");

    private final TagRegistry registry = new TagRegistry();

    @Test
    void theLatestReadingFromATagReplacesTheOneBefore() {
        registry.store(reading("CB:B8:33:4C:88:4F", 20.0, NOW.minusSeconds(30)));
        registry.store(reading("CB:B8:33:4C:88:4F", 21.0, NOW));

        assertEquals(1, registry.size());
        assertEquals(21.0, registry.readings().getFirst().temperature());
    }

    /**
     * Advertisements are broadcast and can be heard out of order. An older one
     * arriving late must not undo a newer reading — nor push the age back, which is
     * what decides whether a tag counts as still reporting.
     */
    @Test
    void anOlderReadingArrivingLateDoesNotWin() {
        registry.store(reading("CB:B8:33:4C:88:4F", 21.0, NOW));
        registry.store(reading("CB:B8:33:4C:88:4F", 20.0, NOW.minusSeconds(30)));

        assertEquals(21.0, registry.readings().getFirst().temperature());
        assertEquals(NOW, registry.readings().getFirst().receivedAt());
    }

    @Test
    void tagsHeardLongAgoAreLeftOutOfWhatIsWorthSending() {
        registry.store(reading("CB:B8:33:4C:88:4F", 21.0, NOW));
        registry.store(reading("CB:B8:33:4C:88:5F", 5.0, NOW.minusSeconds(300)));

        List<Reading> fresh = registry.heardWithin(Duration.ofMinutes(1), NOW);

        assertEquals(1, fresh.size());
        assertEquals(21.0, fresh.getFirst().temperature());
        // But nothing is forgotten: the quiet one is still there to be shown.
        assertEquals(2, registry.readings().size());
    }

    /** Newest first, so the eight that fit in a packet are the eight most recent. */
    @Test
    void whatIsWorthSendingComesNewestFirst() {
        registry.store(reading("CB:B8:33:4C:88:41", 1.0, NOW.minusSeconds(20)));
        registry.store(reading("CB:B8:33:4C:88:42", 2.0, NOW));
        registry.store(reading("CB:B8:33:4C:88:43", 3.0, NOW.minusSeconds(10)));

        assertEquals(List.of(2.0, 3.0, 1.0),
                registry.heardWithin(Duration.ofMinutes(1), NOW).stream()
                        .map(Reading::temperature).toList());
    }

    /**
     * A Thingy:52 sits in the registry beside the RuuviTags and is reported like
     * one of them.
     *
     * <p>It arrives by an entirely different route — a GATT connection rather than
     * an advertisement — and nothing here knows or cares. That is the point of the
     * registry holding readings rather than tags: the sensor that was added last
     * needed no change to the packet, the page or the upload.
     */
    @Test
    void aThingyIsKeptAndReportedAlongsideTheTags() {
        registry.store(reading("CB:B8:33:4C:88:4F", 21.0, NOW));
        registry.store(new ThingyReading("E5:6C:AB:12:34:5F", 19.5, 45.0, (short) -60, NOW));

        assertEquals(2, registry.size());
        assertEquals(List.of("R84F", "T45F"),
                registry.readings().stream().map(Reading::sensorId).toList(),
                "both are worth sending, under identifiers that cannot be confused");
        assertEquals(2, registry.heardWithin(Duration.ofMinutes(1), NOW).size());
        assertTrue(registry.duplicateSensorIds().isEmpty());
    }

    /**
     * A relayed reading is shown but never sent again.
     *
     * <p>This is the one that would be expensive to get wrong. The relay has already
     * forwarded those bytes to the server as the device that measured them, with its
     * own identifier and its own sequence numbers. If they also went into this
     * reader's packet the server would file one thermometer under two devices and
     * have no way to tell they were the same — and the mistake would look like
     * working software.
     */
    @Test
    void aRelayedReadingIsShownButNotSentOnAgain() {
        registry.store(reading("CB:B8:33:4C:88:4F", 21.0, NOW));
        registry.storeRelayed(
                new RelayedReading("SLP1", "CPU", true, 19.6, null, (short) -97, NOW));

        assertEquals(List.of("LoRa SLP1", "R84F"),
                registry.readings().stream().map(Reading::sensorId).sorted().toList(),
                "both belong on the page");
        assertEquals(List.of("R84F"),
                registry.heardWithin(Duration.ofMinutes(1), NOW).stream()
                        .map(Reading::sensorId).toList(),
                "only what this machine heard itself belongs in its own packet");
    }

    /**
     * Two addresses can produce one identifier — twelve bits of a MAC is not
     * unique. Both tags are kept, and the clash is something the reader can be told
     * about rather than a reading that silently disappears.
     */
    @Test
    void twoTagsSharingAnIdentifierAreBothKeptAndReported() {
        // The low twelve bits of both are 0x84F.
        registry.store(reading("CB:B8:33:4C:88:4F", 21.0, NOW));
        registry.store(reading("00:11:22:33:F8:4F", 5.0, NOW));

        assertEquals(2, registry.size());
        assertEquals(List.of("R84F"), registry.duplicateSensorIds());
    }

    @Test
    void distinctTagsAreNotReportedAsAClash() {
        registry.store(reading("CB:B8:33:4C:88:4F", 21.0, NOW));
        registry.store(reading("CB:B8:33:4C:88:5F", 5.0, NOW));

        assertTrue(registry.duplicateSensorIds().isEmpty());
    }

    private static RuuviReading reading(String mac, double temperature, Instant at) {
        String[] parts = mac.split(":");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return new RuuviReading(bytes, temperature, 45.0, 1000.0, 3.0, 4, 0, 1, (short) -60, at);
    }
}
