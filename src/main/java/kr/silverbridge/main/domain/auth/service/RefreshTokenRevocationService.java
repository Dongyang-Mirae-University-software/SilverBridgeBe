package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh token 폐기를 호출 트랜잭션과 분리해 수행한다.
 * <p>
 * 호출자가 폐기 직후 {@code RuntimeException}을 던지는 경로(INACTIVE 차단, 만료/도난 감지 등)에서는
 * 같은 트랜잭션의 {@code delete*}가 모두 롤백되어 실제 DB에 반영되지 않는다.
 * REQUIRES_NEW 로 폐기를 별도 트랜잭션에서 즉시 커밋해 이 시나리오에서도 정상 폐기되도록 한다.
 *
 * @see AccessLogService — REQUIRES_NEW로 호출 트랜잭션과 분리해 로그를 남기는 동일 패턴
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;

    /** 사용자의 모든 refresh token을 별도 트랜잭션에서 폐기. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAll(String userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    /** 특정 refresh token 1건을 별도 트랜잭션에서 폐기. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeOne(RefreshToken token) {
        refreshTokenRepository.delete(token);
    }
}
