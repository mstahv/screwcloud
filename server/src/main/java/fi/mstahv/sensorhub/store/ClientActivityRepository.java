package fi.mstahv.sensorhub.store;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

interface ClientActivityRepository extends JpaRepository<ClientActivity, String> {

    List<ClientActivity> findByLastSeenBefore(Instant cutoff);

    @Transactional
    void deleteByClientId(String clientId);
}
