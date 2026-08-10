package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;

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
        setAlignItems(Alignment.CENTER);
        setSpacing(true);
        setPadding(false);

        Image logo = new Image("icons/screwcloud.svg", "ScrewCloud");
        logo.setWidth("3.5rem");
        logo.setHeight("3.5rem");

        add(logo, new NameAndTagline());
    }

    /**
     * Opens in a new tab: leaving the page would drop the poll and the reader's
     * place, and following a source link is not leaving the application.
     */
    private static class SourceLink extends Anchor {
        SourceLink() {
            super(SOURCE_URL, "Source and documentation on GitHub");
            setTarget("_blank");
            Style style = getStyle();
            style.setFontSize("0.8125rem");
            style.setMarginTop("var(--vaadin-gap-xs)");
        }
    }

    private static class NameAndTagline extends VerticalLayout {
        NameAndTagline() {
            setSpacing(false);
            setPadding(false);

            H1 name = new H1("ScrewCloud");
            Style nameStyle = name.getStyle();
            nameStyle.setMargin("0");
            nameStyle.setFontSize("1.75rem");

            Span tagline = new Span(TAGLINE);
            Style taglineStyle = tagline.getStyle();
            taglineStyle.setColor("var(--vaadin-text-color-secondary)");
            taglineStyle.setFontSize("0.875rem");
            taglineStyle.setMaxWidth("34rem");

            add(name, tagline, new SourceLink());
        }
    }
}
