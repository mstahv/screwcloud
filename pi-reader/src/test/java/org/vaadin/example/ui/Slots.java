package org.vaadin.example.ui;

import java.util.Optional;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.card.Card;

/**
 * Reaches the parts of a component that the locators do not see.
 *
 * <p>The pitfall is general rather than a quirk of {@link Card}:
 * <b>{@code Component.getChildren()} is what a component chooses to expose, while
 * {@code ComponentUtil.getAllChildren()} is what is actually there.</b> The first
 * can be overridden — Card overrides it to return only its content — and the
 * second walks the element tree, virtual children included, so nothing can hide
 * from it.
 *
 * <p>A card's title, header suffix and media are slotted: real element children
 * carrying a {@code slot} attribute, whose {@code getParent()} points back at the
 * card, and which {@code Card.getChildren()} leaves out. So
 * {@code findButton().withAriaLabel("Rename this sensor")} finds nothing while
 * the reader is looking straight at that button.
 *
 * <p>The server module carries the same helper, for the same cards and the same
 * reason.
 */
final class Slots {

    private Slots() {
    }

    /** The first component of the given type anywhere below the root, slots included. */
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
}
