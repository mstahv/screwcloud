package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MeasurementSampleRepository extends JpaRepository<MeasurementSample, Long> {

    @Query("select distinct s.deviceId from MeasurementSample s order by s.deviceId")
    List<String> findDeviceIds();

    @Query("select max(s.receivedAt) from MeasurementSample s where s.deviceId = :deviceId")
    Instant findLatestReceivedAt(String deviceId);

    List<MeasurementSample> findByDeviceIdAndReceivedAtOrderBySensorIdAsc(String deviceId, Instant receivedAt);

    List<MeasurementSample> findByDeviceIdAndSensorIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(
            String deviceId, String sensorId, Instant since);

    /*
       Returns a List rather than a Page on purpose: a Page would issue its own
       count query on every fetch, and the count is asked for separately and only
       when the grid needs it.
    */
    List<MeasurementSample> findByDeviceIdAndSensorId(String deviceId, String sensorId, Pageable pageable);

    long countByDeviceIdAndSensorId(String deviceId, String sensorId);

    /*
       Distinct, because one packet becomes one row per sensor and it is the
       packets whose rhythm matters. Paged rather than capped in the query, so the
       caller decides how much history a median is worth computing from.
    */
    @Query("select distinct s.receivedAt from MeasurementSample s"
            + " where s.deviceId = :deviceId order by s.receivedAt desc")
    List<Instant> findRecentArrivals(String deviceId, Pageable pageable);
}
