package kr.gosky.sso.domain.admin.repository;

import kr.gosky.sso.domain.admin.entity.SsoClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SsoClientRepository extends JpaRepository<SsoClient, Long> {

    // 클라이언트 ID 중복 확인
    boolean existsByClientId(String clientId);

    // 클라이언트 ID로 조회
    Optional<SsoClient> findByClientId(String clientId);
}
