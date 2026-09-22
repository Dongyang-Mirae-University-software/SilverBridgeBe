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
