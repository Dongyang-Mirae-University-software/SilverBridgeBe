package kr.silverbridge.main.domain.checkin.repository;

import kr.silverbridge.main.domain.checkin.entity.CheckinQuestion;
import kr.silverbridge.main.domain.checkin.entity.CheckinSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CheckinQuestionRepository extends JpaRepository<CheckinQuestion, Long> {

    List<CheckinQuestion> findByWardId(String wardId);

    Optional<CheckinQuestion> findByWardIdAndSlot(String wardId, CheckinSlot slot);

    /**
     * 질문 저장(있으면 고침). 두 보호자가 같은 시각을 동시에 처음 저장해도 UNIQUE 위반 409가 나지 않게
     * DB가 충돌을 흡수한다(2026-11-01 점검 L-4, 알림 종류 설정 insertIfAbsent와 같은 방식). slot은 {@code '09:00'} 형식.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO checkin_question (ward_id, slot, question, updated_by, created_at, updated_at)
            VALUES (:wardId, :slot, :question, :updatedBy, now(), now())
            ON CONFLICT (ward_id, slot) DO UPDATE
                SET question = EXCLUDED.question, updated_by = EXCLUDED.updated_by, updated_at = now()
            """, nativeQuery = true)
    int upsert(@Param("wardId") String wardId, @Param("slot") String slot, @Param("question") String question,
               @Param("updatedBy") String updatedBy);
}
