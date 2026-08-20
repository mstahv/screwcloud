package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.dom.Style;

/**
 * A sentence that explains the control above it.
 *
 * <p>Shared rather than redeclared: four views had a private class of this name with
 * this body, which is the point where "named for what it is" turns into copies.
 *
 * <p>Block display, because a hint is a line of its own. As inline spans, two hints
 * next to each other ran together into one broken paragraph — which stayed hidden
 * while every hint happened to sit in a vertical layout that gave each child a row,
 * and appeared the moment they were added to a plain container.
 */
class Hint extends SecondaryText {

    Hint(String text) {
        super(text);
        getStyle().setDisplay(Style.Display.BLOCK);
    }
}
