package fi.mstahv.sensorhub.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.context.annotation.Import;

import fi.mstahv.sensorhub.DatabaseTest;
import fi.mstahv.sensorhub.TestDatabase;
import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;

@DatabaseTest
@Import({TestDatabase.class, MeasurementStore.class})
class MeasurementStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    @Autowired
    private MeasurementStore store;

    @Test
    void latestReadingIsAssembledFromNewestPacket() {
        store.store(packet("LAHT", 1, NOW.minus(Duration.ofMinutes(10)), 20.0));
        store.store(packet("LAHT", 2, NOW, 25.5));

        DeviceMeasurement latest = store.findLatest("LAHT").orElseThrow();

        assertEquals(2, latest.sequence());
        assertEquals(NOW, latest.receivedAt());
        assertEquals(2, latest.sensors().size());
        assertEquals(25.5, latest.sensors().getFirst().temperature(), 0.0001);
    }

    @Test
    void historyIsLimitedToRequestedWindowAndSensor() {
        store.store(packet("LAHT", 1, NOW.minus(Duration.ofHours(30)), 10.0));
        store.store(packet("LAHT", 2, NOW.minus(Duration.ofHours(5)), 15.0));
        store.store(packet("LAHT", 3, NOW, 20.0));

        List<HistoryPoint> history =
                store.history("LAHT", "DHT", NOW.minus(Duration.ofHours(24)));

        // The 30 hour old sample falls outside the window.
        assertEquals(2, history.size());
        assertEquals(15.0, history.getFirst().temperature(), 0.0001);
        assertEquals(20.0, history.get(1).temperature(), 0.0001);
    }

    @Test
    void historyIsOrderedOldestFirst() {
        store.store(packet("LAHT", 2, NOW, 20.0));
        store.store(packet("LAHT", 1, NOW.minus(Duration.ofHours(1)), 15.0));

        List<HistoryPoint> history =
                store.history("LAHT", "DHT", NOW.minus(Duration.ofHours(24)));

        assertTrue(history.getFirst().at().isBefore(history.get(1).at()));
    }

    @Test
    void missingValuesStaySeparateFromZero() {
        store.store(new DeviceMeasurement("LAHT", 1, NOW,
                List.of(new SensorMeasurement("RBF", 21.0, null))));

        SensorMeasurement sensor = store.findLatest("LAHT").orElseThrow().sensors().getFirst();

        assertNull(sensor.humidity());
        assertEquals(21.0, sensor.temperature(), 0.0001);
    }

    @Test
    void deviceIdsAreListedOnceAndSorted() {
        store.store(packet("TALO", 1, NOW, 20.0));
        store.store(packet("LAHT", 1, NOW, 20.0));
        store.store(packet("LAHT", 2, NOW.plusSeconds(60), 21.0));

        assertEquals(List.of("LAHT", "TALO"), store.deviceIds());
    }

    @Test
    void unknownDeviceHasNoLatestReading() {
        assertEquals(Optional.empty(), store.findLatest("EIOO"));
        assertEquals(Optional.empty(), store.findLatest(null));
    }

    /*
       The grid behind the sensor card pages through the whole history, so the
       order has to be total: without a tie breaker the database may return a row
       on two pages or on none, and the reader sees duplicates while scrolling.
    */
    @Test
    void measurementsArePagedNewestFirst() {
        for (int minute = 0; minute < 5; minute++) {
            store.store(packet("LAHT", minute, NOW.plus(Duration.ofMinutes(minute)), 20.0 + minute));
        }

        List<HistoryPoint> firstPage = store.measurements("LAHT", "DHT", PageRequest.of(0, 2));
        List<HistoryPoint> secondPage = store.measurements("LAHT", "DHT", PageRequest.of(1, 2));
        List<HistoryPoint> thirdPage = store.measurements("LAHT", "DHT", PageRequest.of(2, 2));

        assertEquals(List.of(24.0, 23.0), temperatures(firstPage));
        assertEquals(List.of(22.0, 21.0), temperatures(secondPage));
        assertEquals(List.of(20.0), temperatures(thirdPage));
    }

    /*
       Rows from the same packet share an arrival time. Paging by time alone would
       then be ambiguous, which is what the id in the sort order is for.
    */
    @Test
    void pagingIsStableWhenMeasurementsShareAnArrivalTime() {
        store.store(packet("LAHT", 1, NOW, 20.0));
        store.store(packet("LAHT", 2, NOW, 21.0));
        store.store(packet("LAHT", 3, NOW, 22.0));

        List<HistoryPoint> paged = List.of(
                store.measurements("LAHT", "DHT", PageRequest.of(0, 1)).getFirst(),
                store.measurements("LAHT", "DHT", PageRequest.of(1, 1)).getFirst(),
                store.measurements("LAHT", "DHT", PageRequest.of(2, 1)).getFirst());

        // Three distinct rows, no repeats.
        assertEquals(3, paged.stream().map(HistoryPoint::temperature).distinct().count());
    }

    /*
       A sort the grid asks for wins, but the deterministic order is still appended
       to it so equal values do not shuffle between pages.
    */
    @Test
    void aRequestedSortOrderIsHonoured() {
        store.store(packet("LAHT", 1, NOW, 22.0));
        store.store(packet("LAHT", 2, NOW.plus(Duration.ofMinutes(1)), 20.0));

        List<HistoryPoint> ascending = store.measurements("LAHT", "DHT",
                PageRequest.of(0, 10, Sort.by("temperature")));

        assertEquals(List.of(20.0, 22.0), temperatures(ascending));
    }

    @Test
    void countIsPerSensorAndPerDevice() {
        store.store(packet("LAHT", 1, NOW, 20.0));
        store.store(packet("LAHT", 2, NOW.plus(Duration.ofMinutes(5)), 21.0));
        store.store(packet("TALO", 1, NOW, 19.0));

        // Each packet carries a DHT and an RBF row.
        assertEquals(2, store.countMeasurements("LAHT", "DHT"));
        assertEquals(2, store.countMeasurements("LAHT", "RBF"));
        assertEquals(1, store.countMeasurements("TALO", "DHT"));
        assertEquals(0, store.countMeasurements("LAHT", "EIOO"));
    }

    /*
       Unlike history(), this is not limited to a window: the whole point is that
       the grid can reach rows older than the curve shows.
    */
    @Test
    void measurementsAreNotLimitedToTheHistoryWindow() {
        store.store(packet("LAHT", 1, NOW.minus(Duration.ofDays(30)), 5.0));
        store.store(packet("LAHT", 2, NOW, 20.0));

        assertEquals(2, store.countMeasurements("LAHT", "DHT"));
        assertEquals(List.of(20.0, 5.0),
                temperatures(store.measurements("LAHT", "DHT", PageRequest.of(0, 10))));
    }

    private static List<Double> temperatures(List<HistoryPoint> points) {
        return points.stream().map(HistoryPoint::temperature).toList();
    }

    private static DeviceMeasurement packet(String deviceId, int sequence, Instant at, double temperature) {
        return new DeviceMeasurement(deviceId, sequence, at, List.of(
                new SensorMeasurement("DHT", temperature, 40.0),
                new SensorMeasurement("RBF", temperature + 1, 45.0)));
    }
}
