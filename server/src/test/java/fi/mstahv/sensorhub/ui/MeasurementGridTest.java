package fi.mstahv.sensorhub.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;

import fi.mstahv.sensorhub.store.HistoryPoint;
import fi.mstahv.sensorhub.store.MeasurementStore;

/**
 * No Spring context and no database: the store is a mock, because what the grid
 * contributes is the columns and the queries it asks for. That those queries
 * return the right rows is {@code MeasurementStoreTest}'s job.
 *
 * <p>The data provider is exercised directly rather than through the data view.
 * The view's {@code getItems()} goes via the DataCommunicator, which wants an
 * attached component; the provider is the layer the callbacks actually live in.
 */
class MeasurementGridTest {

    private static final Instant NOON = Instant.parse("2026-08-10T12:00:00Z");

    private final MeasurementStore store = mock(MeasurementStore.class);
    private final MeasurementGrid grid = new MeasurementGrid(store, "LAHT", "DHT");

    @Test
    void showsTimeTemperatureAndHumidity() {
        List<String> headers = grid.getColumns().stream()
                .map(column -> column.getHeaderText())
                .toList();

        assertEquals(List.of("Time", "°C", "% RH"), headers);
    }

    /*
       The grid is bound to one sensor of one device. Getting the pair wrong would
       show another sensor's readings under this card's name, and no assertion
       about row counts would catch it.
    */
    @Test
    void asksForItsOwnSensorOnly() {
        when(store.measurements(any(), any(), any()))
                .thenReturn(List.of(new HistoryPoint(NOON, 20.0, 40.0)));

        List<HistoryPoint> rows = dataProvider().fetch(page(0, 50)).toList();

        assertEquals(1, rows.size());
        verify(store).measurements(eq("LAHT"), eq("DHT"), any());
    }

    /*
       The point of the exercise: a scroll asks for one page, not for the whole
       history. Nothing prunes the table, so a grid that loaded everything would
       eventually ask for hundreds of thousands of rows.
    */
    @Test
    void fetchesOnePageAtATime() {
        when(store.measurements(any(), any(), any())).thenReturn(List.of());

        dataProvider().fetch(page(100, 50)).toList();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(store).measurements(any(), any(), pageable.capture());
        assertEquals(50, pageable.getValue().getPageSize());
        // Offset 100 with a page size of 50 is the third page.
        assertEquals(2, pageable.getValue().getPageNumber());
    }

    /*
       The count callback is what lets the grid size its scrollbar exactly instead
       of estimating and re-adjusting as the reader scrolls.
    */
    @Test
    void theExactCountComesFromTheStore() {
        when(store.countMeasurements("LAHT", "DHT")).thenReturn(4321L);

        assertEquals(4321, dataProvider().size(page(0, 50)));
    }

    private static Query<HistoryPoint, Void> page(int offset, int limit) {
        return new Query<>(offset, limit, List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private DataProvider<HistoryPoint, Void> dataProvider() {
        return (DataProvider<HistoryPoint, Void>) grid.getDataProvider();
    }
}
