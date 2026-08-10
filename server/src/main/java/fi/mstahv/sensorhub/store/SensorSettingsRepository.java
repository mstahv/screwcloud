package fi.mstahv.sensorhub.store;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorSettingsRepository extends JpaRepository<SensorSettings, Long> {

    Optional<SensorSettings> findByDeviceIdAndSensorId(String deviceId, String sensorId);
}
