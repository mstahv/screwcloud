package fi.mstahv.sensorhub.ui;

import com.vaadin.flow.component.customfield.CustomField;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;

import fi.mstahv.sensorhub.store.SensorThresholds;
import org.vaadin.firitin.form.FormBinder;

/**
 * The four temperature limits, edited as one value.
 *
 * <p>A {@link CustomField} rather than four fields spread across the settings form,
 * because that is what they are: a set, valid or invalid together. The form that
 * contains this binds a {@link SensorThresholds} — the same record the store takes
 * — instead of four loose numbers it would have to reassemble, and the
 * {@code @IncreasingBands} violation lands here, on the thing it is about, rather
 * than somewhere below the form.
 *
 * <p>The limits are entered as two ranges, which reads more naturally than four
 * independent thresholds: the OK band, and the points beyond which it is an alert.
 * Warning is what falls between the two.
 *
 * <p>Two details make this work without a browser, which matters because every UI
 * test in this application runs without one:
 *
 * <ul>
 * <li>{@code manualValueUpdate} is on. A CustomField otherwise regenerates its value
 * from a DOM {@code change} event, and there is no DOM in those tests — the field
 * would keep answering with the value it was given.
 * <li>the update is driven from the inner binder's own value change listener, and
 * only for changes that came from the client. Without that guard, filling the fields
 * programmatically would report itself as a change the reader made.
 * </ul>
 */
class TemperatureBandsField extends CustomField<SensorThresholds> {

    /*
       Named after the record's components: that is what binds them. The inner binder
       also reads the constraints, which is where the fields would get their limits
       if SensorThresholds stated any.
    */
    private final NumberField alertLow = new TemperatureField("Alert low");
    private final NumberField okLow = new TemperatureField("OK low");
    private final NumberField okHigh = new TemperatureField("OK high");
    private final NumberField alertHigh = new TemperatureField("Alert high");

    private final FormBinder<SensorThresholds> binder;

    TemperatureBandsField() {
        super(SensorThresholds.NONE, true);

        VerticalLayout layout = new VerticalLayout(
                new LimitRow("OK between", okLow, okHigh),
                new LimitRow("Alert below / above", alertLow, alertHigh),
                new Hint("Leave all four empty for the default gauge. "
                        + "Warning is what falls between the OK and alert limits."));
        layout.setPadding(false);
        layout.setSpacing(false);
        layout.setWidthFull();
        add(layout);

        binder = new FormBinder<>(SensorThresholds.class, this);
        binder.addValueChangeListener(event -> {
            if (event.isFromClient()) {
                updateValue();
            }
        });
    }

    @Override
    protected SensorThresholds generateModelValue() {
        return binder.getValue();
    }

    @Override
    protected void setPresentationValue(SensorThresholds thresholds) {
        binder.setValue(thresholds == null ? SensorThresholds.NONE : thresholds);
    }

    /**
     * A temperature limit input. Narrow on purpose: the values are a handful of
     * degrees, and a full width field would suggest more precision than a
     * half-degree step offers.
     *
     * <p>The visible caption belongs to the row, not to either field, so each field
     * carries its own aria label. Without one the four inputs are distinguishable
     * only by position — to a screen reader as much as to a test.
     */
    private static class TemperatureField extends NumberField {
        TemperatureField(String ariaLabel) {
            setAriaLabel(ariaLabel);
            setStep(0.5);
            setWidth("4em");
        }
    }

    /**
     * A caption and a range. Two children rather than four: the caption may take
     * its own line on a narrow popover, and the range is what moves below it.
     */
    private static class LimitRow extends FieldRow {
        LimitRow(String label, NumberField low, NumberField high) {
            add(new RowCaption(label), new Range(low, high));
        }
    }

    /**
     * Two limits and the dash that makes them one thing.
     *
     * <p>This is the one grouping in these forms that earns its keep. Left as loose
     * siblings in the wrapping row, the break landed between the dash and the second
     * field on a 375 pixel screen — a dash hanging at the end of one line and an
     * orphan field starting the next, which looks like a rendering fault rather
     * than a range. A button wraps wherever it likes; a dash does not.
     */
    private static class Range extends HorizontalLayout {
        Range(NumberField low, NumberField high) {
            add(low, new RangeSeparator(), high);
            setAlignItems(Alignment.BASELINE);
            setPadding(false);
        }
    }

    /** Kept at a shared width so the two rows' fields line up. */
    private static class RowCaption extends Span {
        RowCaption(String label) {
            super(label);
            getStyle().setMinWidth("9rem");
        }
    }

    private static class RangeSeparator extends SecondaryText {
        RangeSeparator() {
            super("–");
        }
    }
}
