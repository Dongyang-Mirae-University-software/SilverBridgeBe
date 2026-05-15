package kr.silverbridge.main.domain.auth.repository;

import kr.silverbridge.main.domain.auth.entity.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
}