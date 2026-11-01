package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinSetting;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 안부 알림 발송 기록(V66 {@code checkin_prompt_log}·{@code checkin_guardian_alert_log}) - <b>선점 후 발송</b>.
 * 기록을 먼저 커밋하고(반환 1 = 내가 선점) 그 뒤에 보낸다. 반환 0이면 이미 누가 보냈으니 보내지 않는다.
 * 선점 쓰기는 {@code REQUIRES_NEW} - AFTER_COMMIT 리스너 안에서 이미 커밋된 트랜잭션에 합류해 기록이 사라지지 않게 한다.
 *
 * <p>엔티티 없이 네이티브 쿼리만 쓴다(행은 쓰고 세기만 하고 고치지 않는다). 엔티티 타입 자리는 형식상 {@link CheckinSetting}.</p>
 */
public interface CheckinNotifyLogRepository extends Repository<CheckinSetting, Long> {

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            INSERT INTO checkin_prompt_log (ward_id, check_date, slot, created_at)
            VALUES (:wardId, :date, :slot, now())
            ON CONFLICT (ward_id, check_date, slot) DO NOTHING
            """, nativeQuery = true)
    int claimPrompt(@Param("wardId") String wardId, @Param("date") LocalDate date, @Param("slot") String slot);

    @Query(value = "SELECT ward_id FROM checkin_prompt_log WHERE check_date = :date AND slot = :slot",
            nativeQuery = true)
    List<String> promptedWards(@Param("date") LocalDate date, @Param("slot") String slot);

    @Query(value = "SELECT slot FROM checkin_prompt_log WHERE ward_id = :wardId AND check_date = :date",
            nativeQuery = true)
    List<String> promptedSlots(@Param("wardId") String wardId, @Param("date") LocalDate date);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            INSERT INTO checkin_guardian_alert_log (guardian_id, ward_id, check_date, slot, kind, created_at)
            VALUES (:guardianId, :wardId, :date, :slot, :kind, now())
            ON CONFLICT (guardian_id, ward_id, check_date, slot, kind) DO NOTHING
            """, nativeQuery = true)
    int claimGuardianAlert(@Param("guardianId") String guardianId, @Param("wardId") String wardId,
                           @Param("date") LocalDate date, @Param("slot") String slot, @Param("kind") String kind);

    /** 그 날 이 보호자가 이 피보호자 건으로 같은 종류 알림을 이미 받았는지(미응답은 하루 한 번). */
    @Query(value = """
            SELECT COUNT(*) > 0 FROM checkin_guardian_alert_log
            WHERE guardian_id = :guardianId AND ward_id = :wardId AND check_date = :date AND kind = :kind
            """, nativeQuery = true)
    boolean alertedOnDate(@Param("guardianId") String guardianId, @Param("wardId") String wardId,
                          @Param("date") LocalDate date, @Param("kind") String kind);
}
