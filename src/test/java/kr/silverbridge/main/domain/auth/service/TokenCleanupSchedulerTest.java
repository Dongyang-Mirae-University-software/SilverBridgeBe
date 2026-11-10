package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 만료 refresh token 정리 - 벌크 삭제를 부르고, 실패가 스케줄러를 죽이지 않는다(2026-11-10 2차 점검 L-14). */
@ExtendWith(MockitoExtension.class)
class TokenCleanupSchedulerTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @InjectMocks private TokenCleanupScheduler scheduler;

    @Test
    @DisplayName("만료 토큰을 벌크 삭제로 정리한다")
    void deletesExpiredInBulk() {
        when(refreshTokenRepository.deleteExpiredBefore(any(OffsetDateTime.class))).thenReturn(3);

        scheduler.deleteExpiredTokens();

        verify(refreshTokenRepository).deleteExpiredBefore(any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("삭제가 실패해도 예외가 밖으로 나가지 않는다")
    void failureIsIsolated() {
        when(refreshTokenRepository.deleteExpiredBefore(any(OffsetDateTime.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> scheduler.deleteExpiredTokens()).doesNotThrowAnyException();
    }
}
