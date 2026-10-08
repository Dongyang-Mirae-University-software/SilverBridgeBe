package kr.silverbridge.main.domain.notification.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.silverbridge.main.domain.notification.entity.GuardianNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianNotificationPreferenceRepository extends JpaRepository<GuardianNotificationPreference, Long> {

    Optional<GuardianNotificationPreference> findByGuardianId(String guardianId);

    /** 발송 판정용 벌크 조회. 행이 없는 보호자는 기본값(ON)이다. */
    List<GuardianNotificationPreference> findByGuardianIdIn(Collection<String> guardianIds);

    /**
     * 행이 없을 때만 넣는다. 반환 0 = 이미 있음(동시 요청이 먼저 넣음). 조회 후 save는 동시 최초 저장에서
     * UNIQUE 위반 500이 나므로 DB가 충돌을 흡수하게 한다(알림 채널 설정 USER-G12와 같은 패턴).
     */
    @Modifying
    @Query(value = """
            INSERT INTO guardian_notification_preference (guardian_id, medication_enabled, created_at, updated_at)
            VALUES (:guardianId, :medicationEnabled, now(), now())
            ON CONFLICT (guardian_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("guardianId") String guardianId,
                       @Param("medicationEnabled") boolean medicationEnabled);
}
