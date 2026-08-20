package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

/**
 * The ScrewCloud mark: logo, name, tagline and a link to the source.
 *
 * <p>The source link is here rather than in a footer because the licence expects
 * it to be findable: AGPL section 13 requires that anyone using the application
 * over a network be offered its source, and this is the one component every view
 * starts with.
 *
 * <p>The logo is a static SVG in an {@code <img>} element. An inline SVG would
 * be theme aware, but Vaadin's HTML parser lower-cases attribute names, which
 * breaks SVG's camelCase attributes such as {@code viewBox}. Hence the logo's
 * palette is fixed and chosen to work with either theme.
 */
class BrandHeader extends HorizontalLayout {

    /** The same text as the PWA manifest description. */
    static final String TAGLINE =
            "OSS web app to monitor temperature sensors like Ruuvi Tags and other custom sensors.";

    static final String SOURCE_URL = "https://github.com/mstahv/screwcloud";

    /*
       Nothing configured on the layout itself: a HorizontalLayout's defaults are
       this component. No padding, theme spacing between the mark and the words,
       and the fixed-size logo stays at the top on its own — an image with a set
       height has nothing to stretch. An earlier version set all of that by hand,
       and half of it was restating the defaults.
    */
    BrandHeader() {
        Image logo = new Image("icons/screwcloud.svg", "ScrewCloud");
        logo.setWidth("4rem");
        logo.setHeight("4rem");

        add(logo, new NameAndTagline());
    }

    /**
     * Opens in a new tab: leaving the page would drop its live connection and the
     * reader's place, and following a source link is not leaving the application.
     */
    private static class SourceLink extends Anchor {
        SourceLink() {
            super(SOURCE_URL, "Source and documentation on GitHub");
            setTarget("_blank");
        }
    }

    private static class Tagline extends SecondaryText {
        Tagline() {
            super(TAGLINE);
            // A sentence this long needs a line length, or it runs the width of a
            // desktop window.
            getStyle().setMaxWidth("34rem");
        }
    }

    private static class NameAndTagline extends Column {
        NameAndTagline() {
            // No margin fixes on the H1: the theme already sets headings to margin 0.
            add(new H1("ScrewCloud"), new Tagline(), new SourceLink());
        }
    }
}
