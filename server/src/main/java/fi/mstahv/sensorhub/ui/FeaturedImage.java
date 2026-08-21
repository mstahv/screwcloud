package fi.mstahv.sensorhub.ui;

import java.util.Optional;

/**
 * The bundled featured images a device can choose from: four public-domain
 * paintings of Finnish buildings, cropped widescreen for a card's media slot.
 * A sensor box is usually in a building, and the building is how its owner
 * thinks of it — "the sauna", not "R0BF".
 *
 * <p>Golden-age paintings rather than photographs, for the same reason the
 * sensor cards carry engravings: they are unmistakably pictures, so nobody
 * reads them as a live camera feed from their own yard. Unlike the motifs they
 * are shown as themselves, full colour and full contrast — a featured image is
 * content, not the background of a dial. Artists and sources are in
 * {@code media/CREDITS.md}.
 *
 * <p>What travels to the store is the URL, not a token: the same column holds
 * the user's own image address, so the bundled choices are simply addresses
 * the application happens to ship. {@link #forUrl} is how the picker
 * recognises its own on the way back.
 */
enum FeaturedImage {

    SAUNA("media/buildings/sauna.webp", "Sauna"),
    BARN("media/buildings/barn.webp", "Barn"),
    CROFT("media/buildings/croft.webp", "Croft"),
    FARMHOUSE("media/buildings/farmhouse.webp", "Farmhouse");

    private final String url;
    private final String caption;

    FeaturedImage(String url, String caption) {
        this.url = url;
        this.caption = caption;
    }

    String url() {
        return url;
    }

    String caption() {
        return caption;
    }

    /** The bundled image this URL is, if it is one of ours. */
    static Optional<FeaturedImage> forUrl(String url) {
        for (FeaturedImage image : values()) {
            if (image.url.equals(url)) {
                return Optional.of(image);
            }
        }
        return Optional.empty();
    }
}
