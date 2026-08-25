package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
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

    /*
       The retention sweep's deletes. Batched by id rather than one statement,
       so the nightly job takes many short locks instead of one long one — the
       first purge on a database that has never been swept can be most of the
       table. Native, because JPQL has no LIMIT in a delete.
    */
    @Transactional
    @Modifying
    @Query(value = "delete from measurement_sample where id in"
            + " (select id from measurement_sample where received_at < :cutoff"
            + "  order by received_at limit :batch)", nativeQuery = true)
    int deleteOldestBefore(Instant cutoff, int batch);

    /** Devices whose newest measurement is older than the cutoff. */
    @Query("select s.deviceId from MeasurementSample s"
            + " group by s.deviceId having max(s.receivedAt) < :cutoff")
    List<String> deviceIdsSilentSince(Instant cutoff);

    @Transactional
    void deleteByDeviceId(String deviceId);
}
