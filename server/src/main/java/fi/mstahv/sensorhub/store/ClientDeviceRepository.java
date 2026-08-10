package fi.mstahv.sensorhub.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientDeviceRepository extends JpaRepository<ClientDevice, Long> {

    List<ClientDevice> findByClientIdOrderByDeviceIdAsc(String clientId);

    Optional<ClientDevice> findByClientIdAndDeviceId(String clientId, String deviceId);

    List<ClientDevice> findByAlertOnSilenceTrue();

    boolean existsByClientIdAndDeviceId(String clientId, String deviceId);

    void deleteByClientIdAndDeviceId(String clientId, String deviceId);
}
