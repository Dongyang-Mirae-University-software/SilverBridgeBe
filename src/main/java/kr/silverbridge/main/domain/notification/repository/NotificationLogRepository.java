package kr.silverbridge.main.domain.notification.repository;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
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

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    /**
     * 관리자 알림 이력 목록(최신순).
     *
     * <p>{@code types}는 카테고리 필터를 풀어 쓴 알림 종류 목록이다 - 카테고리가 없으면 {@code typesApplied=false}.
     * 이름 검색은 서비스가 이름으로 사용자 ID를 먼저 찾아 넘긴다({@code keywordApplied}).
     * 빈 IN 절을 피하려고 적용 여부 플래그를 따로 받는다(AdminAnomaly 조회와 같은 방식).</p>
     */
    @Query("""
            SELECT n FROM NotificationLog n
            WHERE n.createdAt >= :from
              AND (:typesApplied = false OR n.type IN :types)
              AND (:result IS NULL OR n.result = :result)
              AND (:keywordApplied = false OR n.wardId IN :userIds OR n.recipientId IN :userIds)
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    Page<NotificationLog> searchForAdmin(@Param("from") OffsetDateTime from,
                                         @Param("typesApplied") boolean typesApplied,
                                         @Param("types") Collection<NotificationType> types,
                                         @Param("result") NotificationLogResult result,
                                         @Param("keywordApplied") boolean keywordApplied,
                                         @Param("userIds") Collection<String> userIds,
                                         Pageable pageable);

    /** 요약 카드용 결과별 건수. 목록과 같은 조건을 받는다(결과 필터는 제외 - 카드가 결과별 숫자다). */
    @Query("""
            SELECT n.result AS result, COUNT(n) AS total FROM NotificationLog n
            WHERE n.createdAt >= :from
              AND (:typesApplied = false OR n.type IN :types)
              AND (:keywordApplied = false OR n.wardId IN :userIds OR n.recipientId IN :userIds)
            GROUP BY n.result
            """)
    List<ResultCount> countByResult(@Param("from") OffsetDateTime from,
                                    @Param("typesApplied") boolean typesApplied,
                                    @Param("types") Collection<NotificationType> types,
                                    @Param("keywordApplied") boolean keywordApplied,
                                    @Param("userIds") Collection<String> userIds);

    // ── 사용자 알림 기록 (2026-11-04, V67) ─────────────────────────────────────────
    // 보이는 행 = 실제로 보낸 알림(result <> NOT_SENT). 조건은 V67 부분 인덱스와 같은 리터럴로 둔다 -
    // 바인딩 파라미터로 바꾸면 PostgreSQL이 부분 인덱스를 고르지 못할 수 있다.

    /** 내 알림 목록(최신순). {@code types}는 묶음 필터를 풀어 쓴 종류 목록, 묶음이 없으면 {@code typesApplied=false}. */
    @Query("""
            SELECT n FROM NotificationLog n
            WHERE n.recipientId = :recipientId
              AND n.result <> kr.silverbridge.main.domain.notification.entity.NotificationLogResult.NOT_SENT
              AND n.createdAt >= :from
              AND (:typesApplied = false OR n.type IN :types)
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    Page<NotificationLog> searchForRecipient(@Param("recipientId") String recipientId,
                                             @Param("from") OffsetDateTime from,
                                             @Param("typesApplied") boolean typesApplied,
                                             @Param("types") Collection<NotificationType> types,
                                             Pageable pageable);

    /** 안 읽은 수. 목록과 같은 보이는 행·기간 조건이다. */
    @Query("""
            SELECT COUNT(n) FROM NotificationLog n
            WHERE n.recipientId = :recipientId
              AND n.readAt IS NULL
              AND n.result <> kr.silverbridge.main.domain.notification.entity.NotificationLogResult.NOT_SENT
              AND n.createdAt >= :from
            """)
    long countUnread(@Param("recipientId") String recipientId, @Param("from") OffsetDateTime from);

    /**
     * 한 건 읽음. 본인 것이고 보이는 행(보관 기간 안)일 때만 바뀐다(0 또는 1). 이미 읽었으면 처음 읽은 시각을 유지하고
     * 1을 돌려준다(멱등). 조회 후 수정이 아니라 UPDATE 한 번이라 동시 요청에도 경합이 없다.
     *
     * <p>{@code from}은 목록과 같은 보관 기간 하한이다 - 목록에 없는 알림을 읽음 처리하지 않는다(2026-11-04 점검 L-2).</p>
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE NotificationLog n SET n.readAt = COALESCE(n.readAt, :now)
            WHERE n.id = :id
              AND n.recipientId = :recipientId
              AND n.result <> kr.silverbridge.main.domain.notification.entity.NotificationLogResult.NOT_SENT
              AND n.createdAt >= :from
            """)
    int markRead(@Param("id") Long id, @Param("recipientId") String recipientId,
                 @Param("from") OffsetDateTime from, @Param("now") OffsetDateTime now);

    /**
     * 본인의 안 읽은(보이는) 알림 전부 읽음. 반환: 이번에 바뀐 건수 - 안 읽은 수({@link #countUnread})와 같은 범위다
     * (보관 정리 전 남은 기간 밖 행을 세지 않는다, 2026-11-04 점검 L-2).
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE NotificationLog n SET n.readAt = :now
            WHERE n.recipientId = :recipientId
              AND n.readAt IS NULL
              AND n.result <> kr.silverbridge.main.domain.notification.entity.NotificationLogResult.NOT_SENT
              AND n.createdAt >= :from
            """)
    int markAllRead(@Param("recipientId") String recipientId, @Param("from") OffsetDateTime from,
                    @Param("now") OffsetDateTime now);

    /** 읽음 요청 실패 시 원인 구분(남의 것 403 / 없음 404)용 - 수신자 ID만 읽는다. */
    @Query("SELECT n.recipientId FROM NotificationLog n WHERE n.id = :id")
    java.util.Optional<String> findRecipientIdById(@Param("id") Long id);

    /** 보관 기간이 지난 이력 삭제. 반환: 삭제 건수. 트랜잭션을 여기 둬서 호출자(스케줄러)가 예외만 다루게 한다. */
    @Transactional
    @Modifying
    @Query("DELETE FROM NotificationLog n WHERE n.createdAt < :threshold")
    int deleteOlderThan(@Param("threshold") OffsetDateTime threshold);

    interface ResultCount {
        NotificationLogResult getResult();

        long getTotal();
    }
}
