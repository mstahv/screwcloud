package fi.mstahv.sensorhub.ui;

import java.util.ArrayList;
import java.util.List;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.customfield.CustomField;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;

/**
 * Picks a device's featured image, as one bindable value: the URL it loads
 * from, or null for none.
 *
 * <p>Three kinds of answer share the one value. "No image" is null; a bundled
 * painting is its application-relative path; "from a URL" is whatever address
 * the user typed. The store never learns which kind it got — it keeps an
 * address — and this field is what turns the address back into the right
 * presentation, recognising the bundled ones by {@link FeaturedImage#forUrl}.
 *
 * <p>The choices are tiles, and the pictures are the tiles: a caption alone
 * would make the reader pick a word and hope. Underneath they are still a
 * radio group — one of a few, keyboard and screen reader included — but the
 * dots are hidden and the chosen tile wears a ring instead; the stylesheet
 * ({@code featured-image-field.css}) explains why. The URL input appears only
 * when "From a URL" is chosen, directly below the tiles, because a text field
 * standing open under a set of pictures reads as a required part of every
 * choice.
 *
 * <p>An address, not an upload — deliberately, for now. The application stores
 * where a picture lives, not its bytes.
 */
@StyleSheet("/styles/featured-image-field.css")
class FeaturedImageField extends CustomField<String> {

    /** The radio's items: null-safe tokens rather than nulls in a selection model. */
    private static final String NONE = "none";
    private static final String CUSTOM = "custom";

    private final RadioButtonGroup<String> choice = new RadioButtonGroup<>();
    private final TextField customUrl = new TextField("Image URL");

    FeaturedImageField(String label) {
        setLabel(label);
        addClassName("featured-image-field");

        List<String> options = new ArrayList<>();
        options.add(NONE);
        for (FeaturedImage image : FeaturedImage.values()) {
            options.add(image.name());
        }
        options.add(CUSTOM);
        choice.setItems(options);
        choice.setRenderer(new ComponentRenderer<>(FeaturedImageField::tile));
        choice.setValue(NONE);

        customUrl.setPlaceholder("https://…");
        customUrl.setHelperText("The address of a picture somewhere. Nothing is uploaded.");
        customUrl.setWidth("min(24rem, 100%)");
        customUrl.setVisible(false);

        /*
           Wired by hand rather than left to CustomField's DOM-event listening,
           so the value is right on the server the moment either part changes —
           which is also what makes the field honest under browserless tests.
        */
        choice.addValueChangeListener(event -> {
            customUrl.setVisible(CUSTOM.equals(event.getValue()));
            updateValue();
        });
        customUrl.addValueChangeListener(event -> updateValue());

        add(choice, customUrl);
    }

    @Override
    protected String generateModelValue() {
        String selected = choice.getValue();
        if (selected == null || NONE.equals(selected)) {
            return null;
        }
        if (CUSTOM.equals(selected)) {
            String url = customUrl.getValue() == null ? "" : customUrl.getValue().strip();
            return url.isEmpty() ? null : url;
        }
        return FeaturedImage.valueOf(selected).url();
    }

    @Override
    protected void setPresentationValue(String url) {
        if (url == null || url.isBlank()) {
            choice.setValue(NONE);
            customUrl.clear();
            return;
        }
        FeaturedImage.forUrl(url).ifPresentOrElse(
                bundled -> {
                    choice.setValue(bundled.name());
                    customUrl.clear();
                },
                () -> {
                    choice.setValue(CUSTOM);
                    customUrl.setValue(url);
                });
    }

    /**
     * One tile: the picture in a frame with its word under it, or — for the
     * choices that have no picture — the word inside the same frame, so the
     * grid stays a grid.
     */
    private static Component tile(String key) {
        Component frame;
        String caption;
        if (NONE.equals(key) || CUSTOM.equals(key)) {
            Div empty = new Div(new Span(NONE.equals(key) ? "No image" : "From a URL"));
            empty.addClassName("fif-frame");
            frame = empty;
            caption = "";
        } else {
            FeaturedImage bundled = FeaturedImage.valueOf(key);
            Image thumbnail = new Image(bundled.url(), bundled.caption());
            thumbnail.addClassName("fif-frame");
            frame = thumbnail;
            caption = bundled.caption();
        }
        Div tile = new Div(frame);
        tile.addClassName("fif-tile");
        if (!caption.isEmpty()) {
            Span word = new Span(caption);
            word.addClassName("fif-caption");
            tile.add(word);
        }
        return tile;
    }
}
