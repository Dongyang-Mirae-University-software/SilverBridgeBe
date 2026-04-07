package kr.silverbridge.main.domain.auth.repository;

import kr.silverbridge.main.domain.auth.entity.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    // 특정 action + 시작 시각 이후 로그 수 조회 (오늘 로그인 수 등)
    @Query("SELECT COUNT(l) FROM AccessLog l WHERE l.action = :action AND l.createdAt >= :from")
    long countByActionAndCreatedAtAfter(@Param("action") String action, @Param("from") LocalDateTime from);
}
