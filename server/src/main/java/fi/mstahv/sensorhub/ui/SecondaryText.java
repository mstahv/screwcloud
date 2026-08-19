package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.html.Span;

import org.vaadin.firitin.util.style.VaadinCssProps;

/**
 * A line of text that supports something else on the page.
 *
 * <p>The colour is the whole definition: the theme's secondary text colour, which is
 * what makes a hint read as a hint next to the value it explains. Ten classes across
 * this package were writing that one style call themselves, each with its own name
 * for the same idea.
 *
 * <p>The names stay. A {@code Hint}, a {@code Forecast} and a {@code Caption} are
 * different things to the person reading the code that builds them, so the classes
 * that say what a line <em>is</em> extend this one, which says only how it looks.
 */
class SecondaryText extends Span {

    SecondaryText() {
        getStyle().setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var());
    }

    SecondaryText(String text) {
        this();
        setText(text);
    }
}
