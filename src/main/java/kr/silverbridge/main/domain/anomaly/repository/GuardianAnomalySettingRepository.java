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

    /**
     * 행이 없을 때만 넣는다. 반환 0 = 이미 있음(동시 요청이 먼저 넣음). 조회 후 save는 같은 보호자의 동시 최초
     * 저장에서 {@code uq_guardian_anomaly_setting} 위반이 나므로 DB가 충돌을 흡수하게 한다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO guardian_anomaly_setting (guardian_id, review_reminder_enabled, created_at, updated_at)
            VALUES (:guardianId, :enabled, now(), now())
            ON CONFLICT (guardian_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@org.springframework.data.repository.query.Param("guardianId") String guardianId,
                       @org.springframework.data.repository.query.Param("enabled") boolean enabled);
}
