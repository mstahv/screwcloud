package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.html.H2;

/**
 * A heading for one region of a page.
 *
 * <p>Smaller than the default H2, which is nearly the size of the application's
 * own name and leaves no visible hierarchy under it. One class for every page's
 * regions, because they are siblings wherever they are: the same rank in the
 * document outline should look like the same rank on screen.
 */
class SectionHeading extends H2 {

    SectionHeading(String text) {
        super(text);
        getStyle().setFontSize("1.25rem");
    }
}
