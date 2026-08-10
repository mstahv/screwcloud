package fi.mstahv.sensorhub.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface HeatSumCounterRepository extends JpaRepository<HeatSumCounter, Long> {

    List<HeatSumCounter> findByDeviceIdAndSensorIdOrderByStartedAtAsc(String deviceId, String sensorId);

    List<HeatSumCounter> findByDeviceId(String deviceId);
}
