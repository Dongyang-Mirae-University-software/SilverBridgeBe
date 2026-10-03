package kr.silverbridge.main.domain.anomaly.repository;

import kr.silverbridge.main.domain.anomaly.entity.AnomalyClip;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface AnomalyClipRepository extends JpaRepository<AnomalyClip, Long> {

    /** 상황 하나의 클립 목록(최신순). 열람 API는 {@code VISIBLE}만 넘긴다. */
    List<AnomalyClip> findByIncidentIdAndStatusOrderByCreatedAtDesc(Long incidentId, AnomalyClipStatus status);

    /**
     * 이력 목록의 대표 클립·건수 - 한 페이지의 상황들에 대해 한 번에 조회한다(건별 조회로 인한 N+1 회피).
     * 상황당 상한(기본 12)이 있어 한 페이지(최대 50건)라도 행 수가 작다.
     */
    List<AnomalyClip> findByIncidentIdInAndStatusOrderByCreatedAtDesc(Collection<Long> incidentIds,
                                                                     AnomalyClipStatus status);

    /** 상황당 상한 판정 - 비공개 클립도 디스크를 쓰므로 상태와 무관하게 센다. */
    long countByIncidentId(Long incidentId);

    /**
     * 오탐 확정 → 비공개. 판정과 같은 트랜잭션(상황 행 쓰기 잠금 안)에서 부른다.
     * 이미 비공개인 클립은 건드리지 않는다 - 24시간 유예의 기준점({@code hidden_at})을 밀지 않기 위해서다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE AnomalyClip c SET c.status = kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus.HIDDEN,
                   c.hiddenAt = :now, c.updatedAt = :now
            WHERE c.incidentId = :incidentId
              AND c.status = kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus.VISIBLE
            """)
    int hideByIncidentId(@Param("incidentId") Long incidentId, @Param("now") OffsetDateTime now);

    /** 오탐에서 번복 → 다시 공개. 이미 물리 삭제된 클립은 행이 없어 돌아오지 않는다(24시간 유예가 끝난 것). */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE AnomalyClip c SET c.status = kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus.VISIBLE,
                   c.hiddenAt = null, c.updatedAt = :now
            WHERE c.incidentId = :incidentId
              AND c.status = kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus.HIDDEN
            """)
    int restoreByIncidentId(@Param("incidentId") Long incidentId, @Param("now") OffsetDateTime now);

    /** 피보호자 탈퇴 정리. */
    List<AnomalyClip> findByWardId(String wardId);

    /** 카메라 삭제 정리 - 삭제된 카메라(세션)의 클립. */
    List<AnomalyClip> findByWardIdAndSessionIdIn(String wardId, Collection<String> sessionIds);

    /** 청소 - 오탐 비공개 후 유예가 지난 것. */
    List<AnomalyClip> findByStatusAndHiddenAtBefore(AnomalyClipStatus status, OffsetDateTime cutoff, Pageable pageable);

    /** 청소 - 보관 기간이 지난 것(상태 무관). */
    List<AnomalyClip> findByCreatedAtBefore(OffsetDateTime cutoff, Pageable pageable);

    /** 청소 - 파일이 사라졌는지 확인할 대상(만든 지 충분히 지난 행). */
    List<AnomalyClip> findByCreatedAtBeforeOrderByIdAsc(OffsetDateTime cutoff);

    /** 청소 - 카메라가 사라졌는지 확인할 세션 목록. */
    @Query("SELECT DISTINCT c.sessionId FROM AnomalyClip c")
    List<String> findDistinctSessionIds();

    /** 청소 - 사라진 카메라(세션)의 클립. */
    List<AnomalyClip> findBySessionIdIn(Collection<String> sessionIds);

    /** 고아 파일 판정용 - 디스크 파일 이름과 대조한다. */
    @Query("SELECT c.fileName FROM AnomalyClip c")
    List<String> findAllFileNames();

    /**
     * 유예가 지난 비공개 클립 1건 삭제 - <b>여전히 비공개일 때만</b> 지운다. 청소와 판정 번복이 겹쳐도
     * 번복으로 돌아온(VISIBLE) 클립을 지우지 않는다. 1이면 지웠다(그때만 파일을 지운다).
     */
    @Transactional
    @Modifying
    @Query("""
            DELETE FROM AnomalyClip c
            WHERE c.id = :id
              AND c.status = kr.silverbridge.main.domain.anomaly.entity.AnomalyClipStatus.HIDDEN
              AND c.hiddenAt < :cutoff
            """)
    int deleteHiddenById(@Param("id") Long id, @Param("cutoff") OffsetDateTime cutoff);

    /** 행 1건 삭제(보관 만료·파일 없음). 1이면 지웠다. */
    @Transactional
    @Modifying
    @Query("DELETE FROM AnomalyClip c WHERE c.id = :id")
    int deleteOneById(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM AnomalyClip c WHERE c.id IN :ids")
    int deleteAllByIdIn(@Param("ids") Collection<Long> ids);
}
