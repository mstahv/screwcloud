package fi.mstahv.sensorhub.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface HeatSumCounterRepository extends JpaRepository<HeatSumCounter, Long> {

    List<HeatSumCounter> findByDeviceIdAndSensorIdOrderByStartedAtAsc(String deviceId, String sensorId);

    List<HeatSumCounter> findByDeviceId(String deviceId);

    /* The retention sweep's side. */

    @Transactional
    void deleteByDeviceId(String deviceId);

    @Query("select distinct c.deviceId from HeatSumCounter c")
    List<String> findCountingDeviceIds();
}
