package kr.silverbridge.main.domain.auth.repository;

import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // 토큰 값으로 조회 (갱신 요청 시 검증)
    Optional<RefreshToken> findByToken(String token);

    // 사용자의 기존 토큰 삭제 (재로그인 시 갱신, 로그아웃 시 제거)
    void deleteByUserId(String userId);

    // 사용자에게 현재 유효한 refresh token이 한 건이라도 있는지 확인 (재사용 감지용)
    boolean existsByUserId(String userId);

    // 만료된 토큰 일괄 삭제 (스케줄러에서 주기적으로 호출)
    void deleteByExpiresAtBefore(OffsetDateTime threshold);
}
