package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.RouterLink;

/**
 * A floating header line for a sub-view: the way back at the left edge, the
 * view's name in the middle, and room for the view's actions at the right edge.
 *
 * <p>A small mobile-first application has no navigation shell: the front page is
 * the menu, and every other view sits one level below it. What such a view needs
 * at the top is a way up and its own name, and iOS settled the form years ago —
 * a chevron in a circle of glass, the title beside it, floating over the content
 * as it scrolls. This is that, drawn with Aura's own materials.
 *
 * <p>Only the arrow gets the glass. The first draft put both in one pill, and it
 * read as a single button whose label was the name of the view you were already
 * on — a back button to the place you are at. The title is bare text, as in
 * iOS's current form language: the circle says "control", the undecorated word
 * says "name", and the difference in dress is what keeps them apart.
 *
 * <p>The back control is an arrow alone. The word next to it ("Devices",
 * "Back") is dead weight once the gesture is learned, which on a phone is the
 * first time it is used — but a control with no text still has to say where it
 * goes, so the destination's name travels as the link's {@code aria-label}
 * instead of as pixels.
 *
 * <p>The bar is {@code position: sticky}: it scrolls away with the page until it
 * would leave, then stays, and the content slides underneath — which is when the
 * glass earns its keep, because a blurred card edge behind the arrow is what
 * says "there is more above" without a border saying it. The bar itself takes no
 * clicks; between the pieces, the page underneath does. The styling lives in
 * {@code sub-view-header.css}, built from Aura's surface tokens with plain
 * fallbacks, so it follows the light and dark schemes for free and degrades to a
 * translucent circle on a theme that is not Aura.
 *
 * <p>The title is an {@code <h1>}: on a view like this it is the page's main
 * heading, and the document outline should say so.
 *
 * <p>Nothing in here knows about this application — deliberately. If the shape
 * proves itself it belongs in an add-on (Viritin), not here — together with
 * {@link NavigationView}, the view around this bar, for the classes that would
 * rather inherit the whole arrangement than compose it.
 */
@StyleSheet("/styles/sub-view-header.css")
public class SubViewHeader extends Header {

    private final H1 title = new H1();
    private final Div actions = new Div();
    private final RouterLink back = new RouterLink();

    /**
     * @param backTarget the view one level up
     * @param backLabel  where the arrow goes, for whoever cannot see that it is
     *                   an arrow — read out as the link's accessible name
     */
    public SubViewHeader(Class<? extends Component> backTarget, String backLabel) {
        addClassName("sub-view-header");

        back.setRoute(backTarget);
        back.add(VaadinIcon.CHEVRON_LEFT.create());
        back.getElement().setAttribute("aria-label", backLabel);
        /*
           aura-surface asks Aura to recompute its surface colour on the link
           itself, which is what lets the stylesheet raise the circle's level.
           Without it the surface tokens arrive already resolved from the page,
           and the level the stylesheet sets would change nothing.
        */
        back.addClassName("aura-surface");

        actions.addClassName("sub-view-header-actions");

        // Three zones of a grid; the stylesheet is what places them.
        add(back, title, actions);
    }

    public SubViewHeader(String title, Class<? extends Component> backTarget, String backLabel) {
        this(backTarget, backLabel);
        setTitle(title);
    }

    /** Separate from construction because a title from a URL parameter arrives later. */
    public void setTitle(String text) {
        title.setText(text);
    }

    /**
     * The way back for a view more than one level down, where "up" is itself a
     * parameterised route — a device's own sub-view goes back to
     * {@code /device/LAHT}, not to {@code /device}. Separate from construction
     * for the same reason as the title: the parameter comes from the URL, which
     * the view only sees later.
     */
    public <T, C extends Component & HasUrlParameter<T>> void setBackTarget(
            Class<? extends C> backTarget, T parameter) {
        back.setRoute(backTarget, parameter);
    }

    /**
     * Puts the view's own controls at the right edge of the bar, mirroring the
     * way back. Buttons land there round, dressed by the stylesheet to match
     * the arrow's glass.
     */
    public void addAction(Component... components) {
        actions.add(components);
    }
}
