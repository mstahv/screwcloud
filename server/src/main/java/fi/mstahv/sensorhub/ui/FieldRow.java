package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

/**
 * A row of form fields that wraps rather than overflows.
 *
 * <p>The forms here live in a popover, and a popover on a phone is narrower than a
 * text field, a number field and a button standing side by side. Without wrapping
 * that showed as a horizontal scrollbar under the row, with the last control off the
 * right-hand edge of the screen.
 *
 * <p>Where the row breaks is left to the browser. At these sizes it hardly matters —
 * the widest child takes its own line and the small ones share the next — and the
 * attempt at controlling it was worse: grouping "the things that belong together"
 * meant inventing pairs the reader never asked for, in wrappers whose settings were
 * copied between call sites.
 *
 * <p>Baseline alignment, because a row of fields is read as a line of text: the
 * field values and the label between them sit on one baseline whatever their boxes
 * are doing.
 */
class FieldRow extends HorizontalLayout {

    FieldRow(Component... fields) {
        super(fields);
        setAlignItems(Alignment.BASELINE);
        setWidthFull();
        setPadding(false);
        setWrap(true);
    }
}
