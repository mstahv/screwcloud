package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.icon.SvgIcon;

/**
 * The buildings a device can wear: a modest set of line drawings, because a
 * sensor box is usually in a building and the building is how its owner thinks
 * of it — "the sauna", not "R0BF".
 *
 * <p>Line drawings on purpose. They are stroke-only SVGs on {@code currentColor}
 * (in {@code icons/buildings/}), so the same file follows the text colour of
 * wherever it stands, both schemes included — the lesson the gauge and the
 * sparkline had to learn the hard way, applied before shipping this time.
 *
 * <p>The store keeps only the token string, and this enum is what the token
 * means. An unknown or absent token is {@link #NONE}, never an error: a
 * database value must not be able to break the page that reads it.
 */
enum DeviceIcon {

    NONE(null, "No icon"),
    HOUSE("house", "House"),
    CABIN("cabin", "Cabin"),
    SAUNA("sauna", "Sauna"),
    BARN("barn", "Barn"),
    GARAGE("garage", "Garage");

    private final String token;
    private final String caption;

    DeviceIcon(String token, String caption) {
        this.token = token;
        this.caption = caption;
    }

    /** What the store keeps; null for {@link #NONE}. */
    String token() {
        return token;
    }

    /** What the picker calls it. */
    String caption() {
        return caption;
    }

    static DeviceIcon fromToken(String token) {
        for (DeviceIcon icon : values()) {
            if (icon.token != null && icon.token.equals(token)) {
                return icon;
            }
        }
        return NONE;
    }

    /**
     * The drawing at the given size, e.g. {@code "3.5rem"}. Only for choices
     * that have one — the caller decides what nothing looks like.
     *
     * <p>{@link SvgIcon} inlines the file, which is what lets the strokes
     * follow {@code currentColor}; an {@code <img>} would freeze them black.
     */
    SvgIcon image(String size) {
        if (token == null) {
            throw new IllegalStateException("NONE has no drawing");
        }
        SvgIcon image = new SvgIcon("icons/buildings/" + token + ".svg");
        image.setSize(size);
        return image;
    }
}
