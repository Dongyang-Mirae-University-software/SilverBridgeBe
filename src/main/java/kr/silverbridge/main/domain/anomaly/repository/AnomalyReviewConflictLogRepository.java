package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewConflictLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface AnomalyReviewConflictLogRepository extends JpaRepository<AnomalyReviewConflictLog, Long> {

    /** 후보 상황들에 대해 이미 처리한(보냈거나 보내지 않기로 한) (상황, 보호자) 조합. */
    List<AnomalyReviewConflictLog> findByIncidentIdIn(Collection<Long> incidentIds);

    /**
     * 동수를 만든 보호자를 "보내지 않고 처리 완료"({@code sent=false})로 기록한다. 이미 행이 있으면 아무것도 하지 않는다.
     *
     * <p>조회 후 저장(exists → save)으로 쓰면 같은 보호자의 동시 응답이나 스케줄러 선점과 겹칠 때 두 트랜잭션이
     * 모두 검사를 통과해 UNIQUE 위반으로 <b>보호자의 응답 자체가 실패</b>한다(2026-09-21 영향 범위 점검 E-1).
     * 원자적 {@code ON CONFLICT DO NOTHING}으로 막는다 - 응답 저장이 안내 기록 때문에 실패해서는 안 된다.</p>
     *
     * @return 새로 기록했으면 1, 이미 있었으면 0
     */
    @Modifying
    @Query(value = """
            INSERT INTO anomaly_review_conflict_log (incident_id, guardian_id, sent, created_at)
            VALUES (:incidentId, :guardianId, FALSE, :createdAt)
            ON CONFLICT (incident_id, guardian_id) DO NOTHING
            """, nativeQuery = true)
    int insertSkipIfAbsent(@Param("incidentId") Long incidentId,
                           @Param("guardianId") String guardianId,
                           @Param("createdAt") OffsetDateTime createdAt);
}
