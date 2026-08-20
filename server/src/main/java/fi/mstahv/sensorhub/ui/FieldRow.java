package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.Component;

import org.vaadin.firitin.layouts.HorizontalFloatLayout;

/**
 * A row of form fields that wraps rather than overflows.
 *
 * <p>The forms here live in a popover, and a popover on a phone is narrower than a
 * text field, a number field and a button standing side by side. Without wrapping
 * that showed as a horizontal scrollbar under the row, with the last control off the
 * right-hand edge of the screen. Where the row breaks is left to the browser — at
 * these sizes it hardly matters, and controlling it meant inventing groupings the
 * reader never asked for.
 *
 * <p>Wrapping and baseline alignment come from {@link HorizontalFloatLayout}; what
 * this class adds is the width. These rows sit in containers that would otherwise
 * shrink-wrap them — a VerticalLayout aligns its children flex-start — and a row
 * has to be as wide as its form for the flexible field in it to have anything to
 * stretch against.
 */
class FieldRow extends HorizontalFloatLayout {

    FieldRow(Component... fields) {
        super(fields);
        setWidthFull();
    }
}
