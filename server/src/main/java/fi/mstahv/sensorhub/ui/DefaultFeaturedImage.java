package fi.mstahv.sensorhub.ui;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import fi.mstahv.sensorhub.store.DeviceSettingsStore;

/**
 * Gives a newly added device a picture, so the front page is a row of cards
 * rather than a row of blanks.
 *
 * <p>A device with no image gets a card with an empty media slot, and a first
 * visit is exactly when there is nothing else on the page to look at. The
 * pictures are bundled and free, so there is no reason for the first impression
 * to be the unfinished one — and the choice is a suggestion, changed in the
 * device's settings by anybody who has a better idea.
 *
 * <h2>The least used one, so a page is not four identical barns</h2>
 *
 * <p>Picked by what the reader's other devices already show rather than at
 * random. Random would put the same painting on two of the first three cards
 * often enough to look like a mistake, which is the opposite of the point; this
 * way the first four devices are four different buildings and the fifth starts
 * again. Ties go to the order they are declared in, so the choice is
 * predictable and a test can state what it should be.
 *
 * <p>Only the bundled paintings are counted. A reader who gave a device a
 * photograph of their own has said nothing about which painting is free.
 *
 * <h2>Never over an existing picture</h2>
 *
 * <p>The image belongs to the device, not to the browser that added it — two
 * people watching the same shed see the same painting. So a device that already
 * has one is left alone, whoever set it and however long ago. Adding a device to
 * a second browser must not repaint it on the first.
 */
final class DefaultFeaturedImage {

    private DefaultFeaturedImage() {
    }

    /**
     * Gives {@code deviceId} a picture if it has none.
     *
     * @param otherDeviceIds the rest of this browser's devices, whose pictures
     *                       decide which one is least used
     */
    static void assignIfMissing(String deviceId, Collection<String> otherDeviceIds,
                                DeviceSettingsStore settings) {
        if (settings.imageUrlFor(deviceId) != null) {
            return;
        }
        List<String> inUse = otherDeviceIds.stream()
                .filter(other -> !other.equals(deviceId))
                .map(settings::imageUrlFor)
                .filter(url -> url != null)
                .toList();
        settings.setImageUrl(deviceId, leastUsedAmong(inUse).url());
    }

    /**
     * The bundled painting fewest of these URLs are already showing.
     *
     * <p>Separated from the store so the rule can be tested as what it is — a
     * function from a handful of strings to a painting — without a database in
     * the way.
     */
    static FeaturedImage leastUsedAmong(Collection<String> imageUrlsInUse) {
        Map<FeaturedImage, Integer> uses = new EnumMap<>(FeaturedImage.class);
        for (FeaturedImage image : FeaturedImage.values()) {
            uses.put(image, 0);
        }
        imageUrlsInUse.stream()
                .map(FeaturedImage::forUrl)
                .flatMap(java.util.Optional::stream)
                .forEach(image -> uses.merge(image, 1, Integer::sum));

        /*
           min() keeps the first of equal entries, and an EnumMap iterates in
           declaration order — so a tie goes to the earliest, which is what makes
           an empty list answer with the first painting rather than an arbitrary
           one.
        */
        return uses.entrySet().stream()
                .min(Comparator.comparingInt(Map.Entry::getValue))
                .orElseThrow()
                .getKey();
    }
}
