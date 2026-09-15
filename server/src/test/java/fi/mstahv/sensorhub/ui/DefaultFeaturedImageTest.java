package fi.mstahv.sensorhub.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The rule is "the one fewest of your other devices are showing", and what it is
 * really for is that a first page does not look like a mistake.
 */
class DefaultFeaturedImageTest {

    @Test
    void theFirstDeviceGetsTheFirstPainting() {
        assertEquals(FeaturedImage.values()[0], DefaultFeaturedImage.leastUsedAmong(List.of()));
    }

    /**
     * The whole point: four devices added one after another are four different
     * pictures, not a coin toss that lands on the same barn twice.
     */
    @Test
    void devicesAddedInARowGetDifferentPaintings() {
        List<String> inUse = new java.util.ArrayList<>();
        for (int i = 0; i < FeaturedImage.values().length; i++) {
            FeaturedImage chosen = DefaultFeaturedImage.leastUsedAmong(inUse);
            assertEquals(FeaturedImage.values()[i], chosen,
                    "device " + (i + 1) + " should get a painting nobody else has");
            inUse.add(chosen.url());
        }
    }

    /** With every painting once, it starts again rather than refusing. */
    @Test
    void afterEveryPaintingIsUsedItBeginsAgain() {
        List<String> everyOne = java.util.Arrays.stream(FeaturedImage.values())
                .map(FeaturedImage::url)
                .toList();

        assertEquals(FeaturedImage.values()[0], DefaultFeaturedImage.leastUsedAmong(everyOne));
    }

    @Test
    void theOneUsedTwiceIsNotChosenAgain() {
        FeaturedImage first = FeaturedImage.values()[0];
        FeaturedImage second = FeaturedImage.values()[1];
        List<String> lopsided = List.of(first.url(), first.url(), second.url());

        FeaturedImage chosen = DefaultFeaturedImage.leastUsedAmong(lopsided);

        assertNotEquals(first, chosen);
        assertNotEquals(second, chosen);
    }

    /**
     * A reader who gave a device a photograph of their own has said nothing
     * about which painting is free, so it counts for nothing.
     */
    @Test
    void somebodysOwnPictureDoesNotCountAsAPainting() {
        List<String> theirOwn = List.of("https://example.invalid/the-shed.jpg");

        assertEquals(FeaturedImage.values()[0], DefaultFeaturedImage.leastUsedAmong(theirOwn));
    }
}
