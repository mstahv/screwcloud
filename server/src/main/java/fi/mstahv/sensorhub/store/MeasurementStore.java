package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fi.mstahv.sensorhub.protocol.DeviceMeasurement;
import fi.mstahv.sensorhub.protocol.SensorMeasurement;

/**
 * Storing and querying measurements.
 *
 * <p>The database is the single source of truth, including for the latest
 * reading. An in-memory cache would be faster, but then the device list would
 * vanish on restart even though the history survived — the UI would be empty
 * until the next packet arrives, which can be five minutes.
 */
@Service
public class MeasurementStore {

    /*
       Paging without a total order is broken paging: the database is free to
       return rows in any order, so a row can appear on two pages or on none. The
       id is there as a tie breaker — two packets can share an arrival time.
    */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("receivedAt"), Sort.Order.desc("id"));

    private final MeasurementSampleRepository repository;

    MeasurementStore(MeasurementSampleRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void store(DeviceMeasurement measurement) {
        repository.saveAll(measurement.sensors().stream()
                .map(sensor -> new MeasurementSample(
                        measurement.deviceId(),
                        sensor.sensorId(),
                        sensor.temperature(),
                        sensor.humidity(),
                        measurement.receivedAt(),
                        measurement.sequence()))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<String> deviceIds() {
        return repository.findDeviceIds();
    }

    /**
     * A device's most recent packet, reassembled. Built from the rows carrying
     * the newest arrival time, which come from the same packet.
     */
    @Transactional(readOnly = true)
    public Optional<DeviceMeasurement> findLatest(String deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        Instant latest = repository.findLatestReceivedAt(deviceId);
        if (latest == null) {
            return Optional.empty();
        }

        List<MeasurementSample> samples =
                repository.findByDeviceIdAndReceivedAtOrderBySensorIdAsc(deviceId, latest);
        if (samples.isEmpty()) {
            return Optional.empty();
        }

        List<SensorMeasurement> sensors = samples.stream()
                .map(sample -> new SensorMeasurement(
                        sample.getSensorId(), sample.getTemperature(), sample.getHumidity()))
                .toList();
        return Optional.of(new DeviceMeasurement(
                deviceId, samples.getFirst().getSequence(), latest, sensors));
    }

    /**
     * One page of a sensor's measurements, newest first — the whole history is
     * reachable this way, however long it has grown.
     *
     * @param pageable typically comes from a lazily loaded Grid; any sort order it
     *        carries is honoured and the deterministic order appended to it
     */
    @Transactional(readOnly = true)
    public List<HistoryPoint> measurements(String deviceId, String sensorId, Pageable pageable) {
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(NEWEST_FIRST));
        return repository.findByDeviceIdAndSensorId(deviceId, sensorId, ordered).stream()
                .map(MeasurementStore::toHistoryPoint)
                .toList();
    }

    /**
     * How many measurements a sensor has in total. An indexed count, so it is
     * cheap enough to ask for up front and let the grid size itself exactly
     * instead of estimating.
     */
    @Transactional(readOnly = true)
    public long countMeasurements(String deviceId, String sensorId) {
        return repository.countByDeviceIdAndSensorId(deviceId, sensorId);
    }

    @Transactional(readOnly = true)
    public List<HistoryPoint> history(String deviceId, String sensorId, Instant since) {
        return repository
                .findByDeviceIdAndSensorIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
                        deviceId, sensorId, since)
                .stream()
                .map(MeasurementStore::toHistoryPoint)
                .toList();
    }

    /**
     * When a device's recent packets arrived, newest first. Used to work out how
     * often it normally reports, and therefore whether it has stopped.
     *
     * @param limit how many arrivals to look at
     */
    @Transactional(readOnly = true)
    public List<Instant> recentArrivals(String deviceId, int limit) {
        return repository.findRecentArrivals(deviceId, PageRequest.of(0, limit));
    }

    private static HistoryPoint toHistoryPoint(MeasurementSample sample) {
        return new HistoryPoint(
                sample.getReceivedAt(), sample.getTemperature(), sample.getHumidity());
    }
}
