package org.vaadin.example.ui;

import com.vaadin.flow.component.html.Span;

import org.vaadin.firitin.util.style.VaadinCssProps;

/**
 * A line of text that supports something else on the page.
 *
 * <p>The colour is the whole definition: the theme's secondary text colour, which
 * is what makes a status line read as a margin note next to the reading it sits
 * under. Two classes in this package were writing that one style call themselves,
 * each with its own name for the same idea — the same duplication the server
 * module had at larger scale, and the same fix: the classes that say what a line
 * <em>is</em> extend this one, which says only how it looks.
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
