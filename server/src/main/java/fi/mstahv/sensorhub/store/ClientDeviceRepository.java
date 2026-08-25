package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ClientDeviceRepository extends JpaRepository<ClientDevice, Long> {

    List<ClientDevice> findByClientIdOrderByDeviceIdAsc(String clientId);

    Optional<ClientDevice> findByClientIdAndDeviceId(String clientId, String deviceId);

    List<ClientDevice> findByAlertOnSilenceTrue();

    List<ClientDevice> findByDeviceId(String deviceId);

    boolean existsByClientIdAndDeviceId(String clientId, String deviceId);

    void deleteByClientIdAndDeviceId(String clientId, String deviceId);

    /* The retention sweep's side: forgetting whole browsers and whole devices. */

    @Transactional
    void deleteByClientId(String clientId);

    @Transactional
    void deleteByDeviceId(String deviceId);

    @Query("select distinct c.deviceId from ClientDevice c")
    List<String> findListedDeviceIds();

    /** When a device last appeared on anyone's list; null if it is on none. */
    @Query("select max(c.addedAt) from ClientDevice c where c.deviceId = :deviceId")
    Instant latestAddedAt(String deviceId);
}
