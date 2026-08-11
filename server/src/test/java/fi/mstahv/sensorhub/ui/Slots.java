package fi.mstahv.sensorhub.ui;

import java.util.Optional;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.card.Card;

/**
 * Reaches the parts of a component that the locators do not see.
 *
 * <p>The pitfall is general, not a quirk of {@link Card}:
 * <b>{@code Component.getChildren()} is what a component chooses to expose, while
 * {@code ComponentUtil.getAllChildren()} is what is actually there.</b> The first
 * can be overridden — Card overrides it to return only its content — and the second
 * traverses the element tree, virtual children included, so it cannot be hidden
 * from.
 *
 * <p>A card's title, subtitle, header suffix and media are slotted. They are real
 * element children carrying a {@code slot} attribute, and their {@code getParent()}
 * points back at the card, but {@code Card.getChildren()} leaves them out — five
 * element children, two component children. So
 * {@code findRouterLink().withText("LAHT")} finds nothing while the reader is
 * looking straight at that link.
 *
 * <p>For diagnosing this kind of thing, the library has its own dump:
 * {@code com.vaadin.browserless.internal.PrettyPrintTreeKt.toPrettyTree(component)},
 * the same one its error messages use. Note the {@code internal} package.
 */
final class Slots {

    private Slots() {
    }

    /**
     * The first component of the given type anywhere below the root, slotted
     * content included.
     *
     * <p>Uses {@code getAllChildren} rather than {@code getChildren}, which is the
     * whole point of this class.
     */
    static <T extends Component> Optional<T> deepFind(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return Optional.of(type.cast(root));
        }
        return ComponentUtil.getAllChildren(root)
                .map(child -> deepFind(child, type))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /** As {@link #deepFind}, for a component a test knows must be there. */
    static <T extends Component> T require(Component root, Class<T> type) {
        return deepFind(root, type).orElseThrow(() -> new AssertionError(
                "No " + type.getSimpleName() + " below " + root.getClass().getSimpleName()));
    }

    /** The card's title, whether it was set as text or as a component. */
    static String titleOf(Card card) {
        Component title = card.getTitle();
        if (title != null) {
            return title.getElement().getTextRecursively().strip();
        }
        String text = card.getTitleAsText();
        return text == null ? "" : text.strip();
    }

    static String subtitleOf(Card card) {
        Component subtitle = card.getSubtitle();
        return subtitle == null ? "" : subtitle.getElement().getTextRecursively().strip();
    }
}
