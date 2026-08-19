package fi.mstahv.sensorhub.ui;

import java.util.List;

import com.vaadin.flow.component.Component;

import org.vaadin.firitin.form.BeanValidationForm;

/**
 * A bound form that is part of a page rather than the page.
 *
 * <p>Every form in this application is one: a section of the front page, the whole
 * of a settings popover, one row of the counter list. {@code BeanValidationForm}
 * assumes the opposite on two counts, and this class is where both assumptions are
 * undone — once, instead of in every subclass with the same comment.
 *
 * <p><b>Sizing.</b> The {@code Div} a Viritin Composite wraps is size full by
 * default. A full height child inside a popover — which sizes itself to its content
 * — is a child with no height at all, and a full height section on a page pushes
 * everything below it off the screen.
 *
 * <p><b>Layout.</b> {@code getFormComponents()} exists to feed the superclass's
 * default layout, one field under another. None of these forms uses it — two fields
 * on a row with a caption is the point of building the content by hand — but the
 * method is abstract, so every form carried an override returning an empty list.
 * It is answered here, finally, and {@link #createContent()} is redeclared abstract
 * in its place: what a form has to say is what it looks like, not that it declines
 * the default.
 */
abstract class RowForm<T> extends BeanValidationForm<T> {

    RowForm(Class<T> type) {
        super(type);
        getContent().setWidthFull();
        getContent().setHeight(null);
    }

    /** Never consulted: every one of these forms lays out its own content. */
    @Override
    protected final List<Component> getFormComponents() {
        return List.of();
    }

    @Override
    protected abstract Component createContent();
}
