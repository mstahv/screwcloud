package org.vaadin.example.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import com.flowingcode.vaadin.addons.relativetime.RelativeTime;
import com.vaadin.browserless.BrowserlessApplicationContext;
import com.vaadin.browserless.BrowserlessUIContext;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.card.Card;

import in.virit.TemperatureGauge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.vaadin.example.history.ReadingHistory;
import org.vaadin.example.lora.LoraPacket;
import org.vaadin.example.lora.LoraReceiver;
import org.vaadin.example.names.SensorNames;
import org.vaadin.example.protocol.MeasurementPacket;
import org.vaadin.example.protocol.SensorReading;
import org.vaadin.example.ruuvi.BleScanner;
import org.vaadin.example.ruuvi.RuuviReading;
import org.vaadin.example.ruuvi.TagRegistry;
import org.vaadin.example.thingy.ThingyReader;
import org.vaadin.example.upstream.ScrewCloudSender;

/**
 * The one page, driven the way a reader drives it.
 *
 * <p>Browserless: real components in a real routing context, no browser and no
 * frontend build. The beans are constructed here rather than by CDI, which is the
 * whole reason the view takes them as constructor arguments.
 */
class LocalViewTest {

    private static final Instant NOW = Instant.now();

    /** Opens the page with these readings already heard, in history and registry. */
    private void inView(Path namesFile, BiConsumer<BrowserlessUIContext, ReadingHistory> body,
                        RuuviReading... heard) {
        inView(namesFile, new LoraReceiver(), body, heard);
    }

    /** The same, with a LoRa radio that has heard something over the air. */
    private void inView(Path namesFile, LoraReceiver lora,
                        BiConsumer<BrowserlessUIContext, ReadingHistory> body,
                        RuuviReading... heard) {
        TagRegistry registry = new TagRegistry();
        ReadingHistory history = new ReadingHistory();
        for (RuuviReading reading : heard) {
            registry.store(reading);
            history.add(reading);
        }
        SensorNames names = new SensorNames(namesFile);

        try (BrowserlessApplicationContext app = BrowserlessApplicationContext.forComponent(
                () -> new LocalView(registry, history, names,
                        new BleScanner(), new ScrewCloudSender("PI01"), lora,
                        new ThingyReader()))) {
            body.accept(app.newUser().newWindow(), history);
        }
    }

    /** A tag that has been heard gets a card, titled with what it is called. */
    @Test
    void aTagThatHasBeenHeardGetsACard(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) -> {
            assertEquals(1, ui.find(Card.class).all().size());
            assertEquals("R84F", Slots.titleOf(ui.find(Card.class).first()));
            assertEquals(null, ui.find(Card.class).first().getSubtitle(),
                    "an unnamed sensor would otherwise show its identifier twice");
            assertTrue(ui.findSpan().withText("45.0 % RH").exists());
            assertTrue(Slots.require(ui.find(Card.class).first(), TemperatureGauge.class)
                    .isVisible(), "the temperature is on the gauge, as on the server's card");
        }, reading("CB:B8:33:4C:88:4F", 21.5, 45.0, NOW));
    }

    /**
     * Naming a tag is the only thing this page can be told, and the name has to
     * outlive the process — so the file is checked, not just the title.
     */
    @Test
    void aTagCanBeNamedAndTheNameIsWrittenDown(@TempDir Path directory) throws IOException {
        Path namesFile = directory.resolve("names.csv");

        inView(namesFile, (ui, history) -> {
            // The rename button is slotted into the card header; see Slots.
            Slots.require(ui.find(Card.class).first(), Button.class).click();
            ui.findTextField().component().setValue("Cold room");
            ui.findButton().withText("Save").click();

            assertEquals("Cold room", Slots.titleOf(ui.find(Card.class).first()));
            assertEquals("R84F",
                    ui.find(Card.class).first().getSubtitle().getElement().getTextRecursively(),
                    "the identifier moves to the subtitle: it is what the server calls this");
        }, reading("CB:B8:33:4C:88:4F", 21.5, 45.0, NOW));

        assertTrue(Files.readString(namesFile).contains("R84F,Cold room"),
                "a name that is not written down is lost on the next restart");
    }

    /** A value the tag does not measure is a dash. Zero would be a reading. */
    @Test
    void aTagWithNoHumidityShowsADash(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) ->
                        assertTrue(ui.findSpan().withText(Readings.MISSING).exists()),
                new RuuviReading(mac("CB:B8:33:4C:88:4F"), 21.5, null, null, null, null,
                        0, 1, (short) -60, NOW));
    }

    /** The first visit, before any tag has been heard, says what to expect. */
    @Test
    void anEmptyPageSaysWhatWillHappen(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) -> {
            assertTrue(ui.findSpan().withTextContaining("Nothing heard yet").exists());
            assertFalse(ui.find(Card.class).exists());
        });
    }

    /** Both statuses are on the page, so a reader can tell which half is unhappy. */
    @Test
    void theRadioAndTheUploadBothReportThemselves(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) -> {
            assertTrue(ui.findSpan().withTextContaining("Bluetooth:").exists());
            assertTrue(ui.findSpan().withTextContaining("ScrewCloud:").exists());
            assertTrue(ui.findSpan().withTextContaining("sending as device PI01").exists(),
                    "the identifier the packets carry is the one thing here that cannot"
                    + " be worked out by looking at the tags");
        });
    }

    /**
     * Each card carries a motif, and no two on the page carry the same one.
     *
     * <p>The picture is there so a sensor can be recognised without reading its
     * name, which fails entirely if two of them share one — and hashing the sensor's
     * own name, the obvious way to do it, collides about nine times in ten at this
     * scale. So the assignment comes from position in the sorted list, and this is
     * the assertion that says so.
     */
    @Test
    void everySensorOnThePageGetsItsOwnMotif(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) -> {
            List<String> motifs = ui.find(Card.class).all().stream()
                    .map(card -> card.getElement().getAttribute("data-motif"))
                    .toList();

            assertEquals(3, motifs.size());
            assertFalse(motifs.contains(null), "every card should have been given a motif");
            assertEquals(motifs.size(), Set.copyOf(motifs).size(),
                    "two sensors sharing a motif makes the picture identify nothing: " + motifs);
        },
                reading("CB:B8:33:4C:88:4F", 21.5, 45.0, NOW),
                reading("CB:B8:33:4C:88:5F", 4.2, 78.0, NOW),
                reading("CB:B8:33:4C:88:6F", 18.0, 40.0, NOW));
    }

    /**
     * The signal strength of what arrives over the air is on the page.
     *
     * <p>This is the number a field test is made of: it is what changes as
     * somebody walks away from the Pi with a node in their hand, and it is the
     * only way to tell a link that is comfortable from one that is about to stop
     * working. The device that sent the packet is named alongside it, because a
     * strength with nothing attached to it says nothing when two nodes are out.
     */
    @Test
    void whatTheLoraRadioHeardIsOnThePageWithItsSignalStrength(@TempDir Path directory) {
        LoraReceiver lora = LoraReceiver.alreadyListening(measurementFrom("SLP1", -97, 7.5));

        inView(directory.resolve("names.csv"), lora, (ui, history) -> {
            assertTrue(ui.findSpan().withTextContaining("LoRa:").exists());
            assertTrue(ui.findSpan().withTextContaining("RSSI -97 dBm").exists());
            assertTrue(ui.findSpan().withTextContaining("SLP1").exists(),
                    "the strength belongs to a device, and there may be more than one");
        });
    }

    /**
     * A listening radio that has heard nothing says so.
     *
     * <p>Standing in a field, "listening and silent" and "not listening" are
     * entirely different situations — one means walk closer, the other means go
     * back and look at the Pi — and empty space under a status line does not
     * tell them apart.
     */
    @Test
    void aLoraRadioThatHasHeardNothingSaysSoRatherThanShowingNothing(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), LoraReceiver.alreadyListening(), (ui, history) -> {
            assertTrue(ui.findSpan().withTextContaining("Nothing heard over the air").exists());
        });
    }

    /** A packet as a node would send it, heard at the given strength. */
    private static LoraPacket measurementFrom(String deviceId, int rssiDbm, double snrDb) {
        byte[] bytes = MeasurementPacket.encode(deviceId, 1,
                List.of(new SensorReading("CPU", 21.5, null)));
        return new LoraPacket(bytes, rssiDbm, snrDb);
    }

    /**
     * A tag that has gone quiet keeps its card — the reading is still the last
     * thing known about that room — but it says so rather than looking current.
     */
    @Test
    void aTagThatHasGoneQuietIsMarked(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) -> {
            assertTrue(ui.find(Card.class).exists(), "the card stays");
            /*
               The badge's own text is only the words: how long it has been silent is
               a component inside it, rendered by the browser, so there is nothing in
               this tree to match on beyond the phrase and the instant.
            */
            assertTrue(ui.find(Badge.class).first().getElement().getTextRecursively()
                            .contains("Nothing heard for"),
                    "and it says the reading is old");
        }, reading("CB:B8:33:4C:88:4F", 4.0, 80.0, NOW.minusSeconds(7200)));
    }

    /**
     * A tag heard a moment ago is not marked, and its card carries the moment it was
     * heard rather than a sentence about it — the words are the browser's to choose,
     * and it keeps them current without this page being polled.
     */
    @Test
    void aTagStillReportingIsNotMarked(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) -> {
            assertFalse(ui.find(Badge.class).exists());
            assertEquals(NOW, ui.find(RelativeTime.class).first().getDateTime());
        }, reading("CB:B8:33:4C:88:4F", 21.5, 45.0, NOW));
    }

    @Test
    void twoTagsAreTwoCards(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) ->
                        assertEquals(2, ui.find(Card.class).all().size()),
                reading("CB:B8:33:4C:88:4F", 21.5, 45.0, NOW),
                reading("CB:B8:33:4C:88:5F", 4.0, 80.0, NOW));
    }

    /**
     * The curve needs two points to be a curve. With one reading there is nothing
     * to draw, and drawing a dot between two identical times would say less than
     * nothing.
     */
    @Test
    void oneReadingDrawsNoCurve(@TempDir Path directory) {
        inView(directory.resolve("names.csv"), (ui, history) ->
                        assertFalse(sparkLineShown(ui)),
                reading("CB:B8:33:4C:88:4F", 21.5, 45.0, NOW));
    }

    /** With an hour behind it, the curve is drawn. */
    @Test
    void anHourOfReadingsDrawsACurve(@TempDir Path directory) {
        RuuviReading[] hour = new RuuviReading[61];
        for (int minute = 0; minute <= 60; minute++) {
            hour[minute] = reading("CB:B8:33:4C:88:4F", 20.0 + minute % 5, 45.0,
                    NOW.minus(Duration.ofMinutes(60 - minute)));
        }

        inView(directory.resolve("names.csv"), (ui, history) -> {
            assertEquals(61, history.pointsFor("CB:B8:33:4C:88:4F").size(),
                    "a point a minute, which is what the sampling keeps");
            assertTrue(sparkLineShown(ui));
        }, hour);
    }

    private static boolean sparkLineShown(BrowserlessUIContext ui) {
        return ui.find(TemperatureSparkLine.class).all().stream()
                .anyMatch(TemperatureSparkLine::isVisible);
    }

    private static RuuviReading reading(String mac, double temperature, double humidity,
                                        Instant at) {
        return new RuuviReading(mac(mac), temperature, humidity, 1000.0, 3.0, 4,
                0, 1, (short) -60, at);
    }

    private static byte[] mac(String mac) {
        String[] parts = mac.split(":");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }
}
