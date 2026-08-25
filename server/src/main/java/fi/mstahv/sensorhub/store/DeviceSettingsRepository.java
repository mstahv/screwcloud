package fi.mstahv.sensorhub.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DeviceSettingsRepository extends JpaRepository<DeviceSettings, Long> {

    Optional<DeviceSettings> findByDeviceId(String deviceId);

    /* The retention sweep's side. */

    @Transactional
    void deleteByDeviceId(String deviceId);

    @Query("select distinct s.deviceId from DeviceSettings s")
    List<String> findConfiguredDeviceIds();
}
