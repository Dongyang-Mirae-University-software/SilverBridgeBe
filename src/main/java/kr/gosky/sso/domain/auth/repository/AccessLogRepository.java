package kr.gosky.sso.domain.auth.repository;

import kr.gosky.sso.domain.auth.entity.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
}
