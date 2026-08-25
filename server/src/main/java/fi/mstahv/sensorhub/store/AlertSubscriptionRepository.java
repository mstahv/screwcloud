package fi.mstahv.sensorhub.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

interface AlertSubscriptionRepository extends JpaRepository<AlertSubscription, Long> {

    Optional<AlertSubscription> findByClientIdAndDeviceIdAndSensorId(
            String clientId, String deviceId, String sensorId);

    List<AlertSubscription> findByDeviceIdAndSensorId(String deviceId, String sensorId);

    /* The retention sweep's side. */

    @Transactional
    void deleteByClientId(String clientId);

    @Transactional
    void deleteByDeviceId(String deviceId);
}
