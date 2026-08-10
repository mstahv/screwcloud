package fi.mstahv.sensorhub.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByClientId(String clientId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    void deleteByClientId(String clientId);

    void deleteByEndpoint(String endpoint);
}
