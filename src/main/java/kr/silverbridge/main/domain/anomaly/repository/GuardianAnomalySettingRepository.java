package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.GuardianAnomalySetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GuardianAnomalySettingRepository extends JpaRepository<GuardianAnomalySetting, Long> {

    Optional<GuardianAnomalySetting> findByGuardianId(String guardianId);

    /** 발송 판정용 벌크 조회. 행이 없는 보호자는 기본값(ON)으로 취급한다. */
    List<GuardianAnomalySetting> findByGuardianIdIn(Collection<String> guardianIds);
}
