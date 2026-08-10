package fi.mstahv.sensorhub.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientDeviceRepository extends JpaRepository<ClientDevice, Long> {

    List<ClientDevice> findByClientIdOrderByDeviceIdAsc(String clientId);

    boolean existsByClientIdAndDeviceId(String clientId, String deviceId);

    void deleteByClientIdAndDeviceId(String clientId, String deviceId);
}
