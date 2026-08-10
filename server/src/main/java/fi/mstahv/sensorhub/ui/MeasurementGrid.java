package fi.mstahv.sensorhub.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;

import fi.mstahv.sensorhub.store.HistoryPoint;
import fi.mstahv.sensorhub.store.MeasurementStore;

/**
 * Every reading a sensor has sent, as rows.
 *
 * <p>The card itself answers "what is it now" — the gauge and the curve. This
 * answers "what were the actual numbers", which is the question the curve cannot:
 * it has no scale marks, it stops at 24 hours, and humidity does not appear on it
 * at all.
 *
 * <p>Rows are fetched a page at a time with {@code setItemsPageable}, so the size
 * of the history does not matter. It grows by roughly 290 rows per sensor per day
 * and nothing prunes it, so loading all of it would eventually be a problem — and
 * would be wasted work regardless, since a reader looks at a dozen rows.
 *
 * <p>Newest first, because a table that is scrolled is read from the top and the
 * recent rows are the interesting ones. That is the opposite of the curve, which
 * runs left to right in time.
 */
class MeasurementGrid extends Grid<HistoryPoint> {

    /*
       Only the time of day in the cell: consecutive rows are minutes apart, so
       the date repeats, and the card is 15rem wide. The full timestamp, seconds
       included, is a tooltip away.
    */
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter FULL =
            DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.ROOT);

    /*
       Resolved once here rather than per cell: it is the same for every row, and
       it is the reader's own zone, as everywhere else a time is shown.
    */
    private final ZoneId zone = ClientTimeZone.get();

    MeasurementGrid(MeasurementStore measurements, String deviceId, String sensorId) {
        /*
           LUMO_COMPACT would be the obvious variant for a table this small, but
           this application runs the Aura theme, where the LUMO_* variants have no
           effect. There is no AURA_COMPACT and no theme agnostic COMPACT, so only
           the variants that exist in both themes are used here.
        */
        addThemeVariants(GridVariant.NO_BORDER, GridVariant.ROW_STRIPES);

        // A fixed height keeps a long history from stretching the card down the
        // page; the grid scrolls instead.
        setHeight("14rem");

        addColumn(point -> at(point.at(), CLOCK))
                .setHeader("Time")
                .setTooltipGenerator(point -> at(point.at(), FULL))
                .setAutoWidth(true);
        addColumn(point -> Readings.format(point.temperature(), "%.2f"))
                .setHeader("°C").setAutoWidth(true);
        addColumn(point -> Readings.format(point.humidity(), "%.1f"))
                .setHeader("% RH").setAutoWidth(true);

        /*
           The second callback gives the exact row count. Without it the grid
           estimates its size and keeps adjusting the scrollbar while the reader
           scrolls; here the count is one indexed query, so it is cheaper to ask
           than to guess.
        */
        setItemsPageable(
                pageable -> measurements.measurements(deviceId, sensorId, pageable),
                pageable -> measurements.countMeasurements(deviceId, sensorId));
    }

    /** Re-reads the rows, for when a new measurement has arrived. */
    void refresh() {
        getLazyDataView().refreshAll();
    }

    private String at(Instant instant, DateTimeFormatter format) {
        return format.format(instant.atZone(zone));
    }
}
