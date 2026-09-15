package fi.mstahv.sensorhub.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FirmwareBuildRepository extends JpaRepository<FirmwareBuild, Long> {

    Optional<FirmwareBuild> findByDeviceId(String deviceId);

    @Query("select f.deviceId from FirmwareBuild f")
    List<String> findDeviceIds();
}
