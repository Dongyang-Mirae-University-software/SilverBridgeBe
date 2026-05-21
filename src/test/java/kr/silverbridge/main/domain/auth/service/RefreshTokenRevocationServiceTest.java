package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.verify;

/**
 * {@link RefreshTokenRevocationService} 위임 검증.
 * REQUIRES_NEW 전파 자체(호출 트랜잭션 롤백에도 폐기 유지)는 Spring 통합 테스트 영역이며,
 * 여기서는 각 메서드가 올바른 repository 연산에 위임하는지 단위로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenRevocationServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks private RefreshTokenRevocationService revocationService;

    @Test
    @DisplayName("revokeAll → 사용자의 모든 refresh token 삭제(deleteByUserId)에 위임")
    void revokeAllDeletesByUserId() {
        revocationService.revokeAll("user-1");

        verify(refreshTokenRepository).deleteByUserId("user-1");
    }

    @Test
    @DisplayName("revokeOne → 특정 token 1건 삭제(delete)에 위임")
    void revokeOneDeletesSingleToken() {
        RefreshToken token = RefreshToken.builder()
                .userId("user-1")
                .token("some-token")
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .build();

        revocationService.revokeOne(token);

        verify(refreshTokenRepository).delete(token);
    }
}
