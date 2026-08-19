package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.vaadin.firitin.util.style.VaadinCssProps;

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

    BrandHeader() {
        /*
           Aligned to the top rather than centred. Centring is right for a mark and
           one line of text, and this is a mark and four: on a phone the tagline
           wraps to three lines and the logo drifted to the middle of them, sitting
           opposite the words rather than beside the name it belongs to. From the
           top it lines up with "ScrewCloud", which is the thing it is the mark for.
        */
        setAlignItems(Alignment.START);
        setPadding(false);

        /*
           Room, in moderation. The block had none: the name sat directly on the
           tagline and the tagline on the link, so four different things — a mark, a
           name, a sentence and a link — read as one paragraph of unequal type. A
           step of space between them is enough to separate them; more would make a
           masthead of what is only a heading.
        */
        getStyle().setGap(VaadinCssProps.GAP_L.var());
        getStyle().set("padding-block", VaadinCssProps.PADDING_S.var());

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

    private static class NameAndTagline extends VerticalLayout {
        NameAndTagline() {
            setPadding(false);
            getStyle().setGap(VaadinCssProps.GAP_XS.var());

            H1 name = new H1("ScrewCloud");
            // An H1's default margins would push the logo out of line with it.
            name.getStyle().setMargin("0");

            add(name, new Tagline(), new SourceLink());
        }
    }
}
