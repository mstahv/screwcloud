package fi.mstahv.sensorhub.store;

import java.util.Optional;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface SensorSettingsRepository extends JpaRepository<SensorSettings, Long> {

    Optional<SensorSettings> findByDeviceIdAndSensorId(String deviceId, String sensorId);

    /* The retention sweep's side. */

    @Transactional
    void deleteByDeviceId(String deviceId);

    @Query("select distinct s.deviceId from SensorSettings s")
    List<String> findConfiguredDeviceIds();
}
