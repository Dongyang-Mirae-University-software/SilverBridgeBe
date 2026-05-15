package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.global.enums.AccessAction;
import kr.silverbridge.main.global.jwt.JwtProperties;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountEventListenerTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private AccessLogService accessLogService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private JwtProperties jwtProperties;

    @InjectMocks private UserAccountEventListener listener;

    @Test
    @DisplayName("UserWithdrawnEvent 수신 시 토큰 삭제 + WITHDRAW 접속 로그 기록")
    void handleWithdrawn_토큰삭제_및_WITHDRAW_로그() {
        UserWithdrawnEvent event = new UserWithdrawnEvent("user-1", "127.0.0.1", "test-agent");

        listener.handleWithdrawn(event);

        verify(refreshTokenRepository).deleteByUserId("user-1");
        verify(accessLogService).log("user-1", AccessAction.WITHDRAW, "127.0.0.1", "test-agent");
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("PasswordChangedEvent 수신 시 토큰 삭제 + access token 무효화 도장 + 접속 로그 호출 없음")
    void handlePasswordChanged_토큰삭제_및_무효화도장() {
        // access token expiration TTL이 그대로 invalidation 키 TTL로 적용되는지 함께 검증
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(1_800_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        PasswordChangedEvent event = new PasswordChangedEvent("user-2");

        long before = System.currentTimeMillis();
        listener.handlePasswordChanged(event);
        long after = System.currentTimeMillis();

        verify(refreshTokenRepository).deleteByUserId("user-2");
        verify(valueOperations).set(
                eq(RedisKeys.PASSWORD_INVALIDATE + "user-2"),
                anyString(),
                eq(1_800_000L),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(accessLogService, never()).log(anyString(), org.mockito.ArgumentMatchers.any());
        // 저장된 timestamp 값이 호출 시각 범위 내인지 확인
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(RedisKeys.PASSWORD_INVALIDATE + "user-2"),
                captor.capture(),
                anyLong(),
                eq(TimeUnit.MILLISECONDS)
        );
        long savedAt = Long.parseLong(captor.getValue());
        assertThat(savedAt).isBetween(before, after);
    }
}
