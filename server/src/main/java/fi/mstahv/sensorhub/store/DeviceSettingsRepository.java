package fi.mstahv.sensorhub.store;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceSettingsRepository extends JpaRepository<DeviceSettings, Long> {

    Optional<DeviceSettings> findByDeviceId(String deviceId);
}
