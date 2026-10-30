package kr.silverbridge.main.domain.sos.repository;

import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface SosEventRepository extends JpaRepository<SosEvent, Long> {

    /**
     * 보호자 화면용 SOS 이력 조회 — 여러 피보호자의 이력을 발생 최신순으로 합쳐서 페이징한다.
     *
     * <p>정렬을 메서드 이름에 고정했으므로 호출부는 {@code Sort} 없는 {@code PageRequest}를 넘긴다
     * (둘 다 주면 정렬이 이중으로 적용된다). {@code idx_sos_events_ward_created (ward_id, created_at DESC)}를
     * 활용한다.</p>
     *
     * @param wardIds 조회 대상 피보호자 ID들 — <b>인가된(ACTIVE 연결) 목록만</b> 넘겨야 한다(IDOR 방지)
     */
    Page<SosEvent> findByWardIdInOrderByCreatedAtDesc(Collection<String> wardIds, Pageable pageable);

    /**
     * 발생 경로 필터를 건 보호자 이력 조회(SOS-G12). 정렬·인가 전제는 위 메서드와 같다.
     *
     * @param wardIds 조회 대상 피보호자 ID들 — <b>인가된(ACTIVE 연결) 목록만</b> 넘겨야 한다(IDOR 방지)
     */
    Page<SosEvent> findByWardIdInAndTriggerTypeOrderByCreatedAtDesc(Collection<String> wardIds,
                                                                     SosTriggerType triggerType,
                                                                     Pageable pageable);

    /**
     * 경로별 전체 건수 - 보호자 이력 화면의 탭 숫자용(SOS-G12).
     *
     * <p>페이지와 무관하게 전체 기준으로 센다. 화면이 현재 페이지(최대 50건)로 세면 2페이지 이후에만 있는 경로가
     * "0건"으로 보인다. 건수가 0인 경로는 행이 없으므로 호출부가 0으로 채운다(인가된 범위 안에서 실제로 센 값이라
     * "모르는 값"이 아니다).</p>
     *
     * @param wardIds 집계 대상 피보호자 ID들 — <b>인가된(ACTIVE 연결) 목록만</b> 넘겨야 한다(IDOR 방지)
     */
    @Query("""
            SELECT e.triggerType AS triggerType, COUNT(e) AS total FROM SosEvent e
            WHERE e.wardId IN :wardIds
            GROUP BY e.triggerType
            """)
    List<TriggerTypeCount> countByTriggerType(@Param("wardIds") Collection<String> wardIds);

    /**
     * 최근 집계 창 안에서 같은 피보호자가 발생시킨 SOS 건수 - 보호자 알림 문구의 "N번째" 표기용.
     *
     * <p>연타는 이력에 전부 남지만 알림은 쿨다운으로 합쳐진다({@code SosNotificationCooldown}). 그래서
     * 두 번째 이후 알림이 첫 번째와 문구가 같으면 보호자가 "새 상황"과 "같은 상황이 계속됨"을 구분할 수 없다.
     * 생략된 연타를 이 카운트로 되살려 문구에 담는다.</p>
     *
     * <p>{@code idx_sos_events_ward_created (ward_id, created_at DESC)}를 그대로 타므로 별도 인덱스가 필요 없다.
     * 발송 직전에 한 번만 호출한다 - 쿨다운에 막힌 연타는 이 쿼리에 도달하지 않는다.</p>
     *
     * @param wardId SOS를 발생시킨 피보호자 ID
     * @param from   집계 창의 시작 시각(이 시각 이후 발생분만 센다). 달력상 "오늘"이 아니라 흐르는 창이라
     *               타임존과 무관하다 - KST 변환이 필요 없다
     */
    long countByWardIdAndCreatedAtGreaterThanEqual(String wardId, OffsetDateTime from);

    /**
     * 탈퇴로 피보호자가 사라진(ward_id NULL) 익명 이력의 위치 문구를 비운다(2026-10-31 전체 점검 D-2).
     * 이력(언제·어떤 경로로)은 감사 목적으로 남기되, 집 안 위치 같은 자유 문구는 붙들지 않는다. 반환: 비운 건수.
     */
    @Transactional
    @Modifying
    @Query("UPDATE SosEvent e SET e.location = NULL WHERE e.wardId IS NULL AND e.location IS NOT NULL")
    int clearLocationOfWithdrawn();

    /**
     * 보호자 대시보드용 - 인가된 피보호자 범위에서 {@code from} 이후 발생한 SOS 건수("이번 달").
     *
     * @param wardIds <b>인가된(ACTIVE 연결) 목록만</b> 넘겨야 한다(IDOR 방지)
     */
    long countByWardIdInAndCreatedAtGreaterThanEqual(Collection<String> wardIds, OffsetDateTime from);

    /**
     * 연타 묶음 공통 CTE(2026-10-30). 같은 피보호자·같은 경로에서 <b>앞 누름과의 간격</b>이 {@code :gapSeconds} 이하면
     * 같은 묶음이다 - 시계 기준 칸이 아니라 연쇄라 30초 간격으로 5분 동안 누르면 한 묶음이 된다.
     *
     * <p>{@code marked.is_start}는 묶음의 첫 누름이면 1이고, {@code numbered.grp}는 그 누적합(묶음 번호)이다. 묶음을
     * DB 안에서 만드는 이유는 페이지 경계다 - 페이지로 자른 뒤 자바에서 묶으면 경계에 걸친 묶음이 두 칸으로 갈린다.</p>
     *
     * <p>보호자 연락(GUARDIAN_CALL)은 <b>전화받은 보호자</b>도 묶음 기준이다(2026-10-30) - 1분 안이어도 다른 보호자에게 건
     * 전화는 다른 줄이다. SOS 버튼은 {@code called_guardian_id}가 늘 NULL이라 묶음이 그대로다. 전화받은 보호자가 기록되지 않은
     * 보호자 연락(옛 이력·확인 실패)끼리는 NULL을 같은 값('')으로 보고 묶는다.</p>
     *
     * <p>{@code :triggerType}은 빈 문자열이면 전체다(null 바인딩의 타입 추론 문제를 피한다). 경로로 파티션을 나누므로
     * 필터를 걸어도 묶음은 전체 조회 때의 그 경로 묶음과 같다. {@code ward_id IN}이라 탈퇴로 {@code ward_id}가 NULL인
     * 익명 이력은 애초에 대상이 아니다.</p>
     */
    String PRESS_GROUP_CTE = """
            WITH marked AS (
                SELECT id, ward_id, trigger_type, called_guardian_id, created_at,
                       CASE WHEN LAG(created_at) OVER w IS NULL
                              OR created_at - LAG(created_at) OVER w > :gapSeconds * INTERVAL '1 second'
                            THEN 1 ELSE 0 END AS is_start
                FROM sos_event
                WHERE ward_id IN (:wardIds)
                  AND (:triggerType = '' OR trigger_type = :triggerType)
                WINDOW w AS (PARTITION BY ward_id, trigger_type, COALESCE(called_guardian_id, '') ORDER BY created_at, id)
            ), numbered AS (
                SELECT id, ward_id, trigger_type, called_guardian_id, created_at,
                       SUM(is_start) OVER (PARTITION BY ward_id, trigger_type, COALESCE(called_guardian_id, '')
                                           ORDER BY created_at, id) AS grp
                FROM marked
            )
            """;

    /**
     * 보호자 이력 연타 묶음 한 페이지 - 묶음의 마지막 누름 최신순(2026-10-30).
     *
     * <p>시각·위치는 프로젝션으로 받지 않고 묶음의 최신·최초 이력 ID만 돌려준다. 호출부가 엔티티로 다시 읽어 기존
     * 항목과 같은 매핑을 쓴다.</p>
     *
     * @param wardIds     <b>인가된(ACTIVE 연결) 목록만</b> 넘겨야 한다(IDOR 방지)
     * @param triggerType 경로 이름. 빈 문자열이면 전체
     * @param gapSeconds  같은 묶음으로 볼 앞 누름과의 최대 간격(초)
     */
    @Query(value = PRESS_GROUP_CTE + """
            SELECT (array_agg(id ORDER BY created_at DESC, id DESC))[1] AS "latestId",
                   (array_agg(id ORDER BY created_at ASC, id ASC))[1] AS "firstId",
                   COUNT(*) AS "pressCount"
            FROM numbered
            GROUP BY ward_id, trigger_type, COALESCE(called_guardian_id, ''), grp
            ORDER BY MAX(created_at) DESC, 1 DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<PressGroupRow> findPressGroups(@Param("wardIds") Collection<String> wardIds,
                                        @Param("triggerType") String triggerType,
                                        @Param("gapSeconds") int gapSeconds,
                                        @Param("limit") int limit,
                                        @Param("offset") long offset);

    /** {@link #findPressGroups}와 같은 조건의 묶음 수 - 묶음 모드의 {@code totalElements}. */
    @Query(value = PRESS_GROUP_CTE + """
            SELECT COALESCE(SUM(is_start), 0) FROM marked
            """, nativeQuery = true)
    long countPressGroups(@Param("wardIds") Collection<String> wardIds,
                          @Param("triggerType") String triggerType,
                          @Param("gapSeconds") int gapSeconds);

    /**
     * 경로별 묶음 수 - 묶음 모드의 탭 숫자. 필터·페이지와 무관하게 조회 범위 전체를 센다({@link #countByTriggerType}의
     * 묶음 버전).
     *
     * @param wardIds <b>인가된(ACTIVE 연결) 목록만</b> 넘겨야 한다(IDOR 방지)
     */
    @Query(value = PRESS_GROUP_CTE + """
            SELECT trigger_type AS "triggerType", SUM(is_start) AS "total"
            FROM marked
            GROUP BY trigger_type
            """, nativeQuery = true)
    List<PressGroupCount> countPressGroupsByTriggerType(@Param("wardIds") Collection<String> wardIds,
                                                        @Param("triggerType") String triggerType,
                                                        @Param("gapSeconds") int gapSeconds);

    /** 연타 묶음 한 행 - 묶음의 최신·최초 이력 ID와 누름 수. */
    interface PressGroupRow {
        Long getLatestId();

        Long getFirstId();

        Long getPressCount();
    }

    /** 경로별 묶음 수 프로젝션(네이티브라 경로는 문자열로 받는다). */
    interface PressGroupCount {
        String getTriggerType();

        Long getTotal();
    }

    /** 경로별 건수 프로젝션. */
    interface TriggerTypeCount {
        SosTriggerType getTriggerType();

        long getTotal();
    }
}
