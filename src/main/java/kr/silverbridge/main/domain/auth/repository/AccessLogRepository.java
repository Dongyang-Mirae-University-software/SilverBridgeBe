package kr.silverbridge.main.domain.auth.repository;

import kr.silverbridge.main.domain.auth.entity.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    /** 보관 기간이 지난 접속 로그 삭제(2026-10-31 전체 점검 D-2). 반환: 삭제 건수. */
    @Transactional
    @Modifying
    @Query("DELETE FROM AccessLog a WHERE a.createdAt < :threshold")
    int deleteOlderThan(@Param("threshold") OffsetDateTime threshold);

    /**
     * 탈퇴로 회원이 사라진(user_id NULL) 접속 로그의 IP·브라우저 정보를 비운다(2026-10-31 D-2).
     * 행(무엇을 언제 했는가)은 보관 기간까지 남기고, 사람을 다시 가리킬 수 있는 값만 지운다. 반환: 비운 건수.
     */
    @Transactional
    @Modifying
    @Query("""
            UPDATE AccessLog a SET a.ipAddress = NULL, a.userAgent = NULL
            WHERE a.userId IS NULL AND (a.ipAddress IS NOT NULL OR a.userAgent IS NOT NULL)
            """)
    int anonymizeWithdrawn();
}
