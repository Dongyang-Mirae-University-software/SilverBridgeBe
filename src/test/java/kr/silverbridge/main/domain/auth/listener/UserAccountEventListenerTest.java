package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserRoleChangedEvent;
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
    @DisplayName("UserWithdrawnEvent 수신 시 토큰 삭제 + access token 무효화 도장 + WITHDRAW 접속 로그 기록 (A-USER-1)")
    void handleWithdrawn_토큰삭제_무효화_및_WITHDRAW_로그() {
        // 탈퇴 시에도 비밀번호 변경과 동일하게 무효화 도장을 찍어 기존 access token을 즉시 차단한다 (A-USER-1)
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(1_800_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        UserWithdrawnEvent event = new UserWithdrawnEvent("user-1", "127.0.0.1", "test-agent");

        listener.handleWithdrawn(event);

        verify(refreshTokenRepository).deleteByUserId("user-1");
        verify(valueOperations).set(
                eq(RedisKeys.PASSWORD_INVALIDATE + "user-1"),
                anyString(),
                eq(1_800_000L),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(accessLogService).log("user-1", AccessAction.WITHDRAW, "127.0.0.1", "test-agent");
    }

    @Test
    @DisplayName("UserRoleChangedEvent 수신 시 토큰 삭제 + access token 무효화 도장 - 옛 역할 토큰이 만료까지 살아 있으면 안 된다 (2026-09-10 M-1)")
    void handleRoleChanged_토큰삭제_및_무효화도장() {
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(1_800_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        listener.handleRoleChanged(new UserRoleChangedEvent("user-4"));

        verify(refreshTokenRepository).deleteByUserId("user-4");
        verify(valueOperations).set(
                eq(RedisKeys.PASSWORD_INVALIDATE + "user-4"),
                anyString(),
                eq(1_800_000L),
                eq(TimeUnit.MILLISECONDS)
        );
        verify(accessLogService, never()).log(anyString(), org.mockito.ArgumentMatchers.any());
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
        // 초 단위(내림)로 저장한다 - 같은 초에 새로 발급된 토큰을 살리기 위해 (D2, AUTH-G06)
        long savedAt = Long.parseLong(captor.getValue());
        assertThat(savedAt).isBetween(before / 1000, after / 1000);
    }

    @Test
    @DisplayName("PasswordChangedEvent 수신 시 로그인 잠금·실패 횟수 키를 삭제한다 - 재설정 후 바로 로그인 가능 (AUTH-G01)")
    void handlePasswordChanged_로그인잠금_해제() {
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(1_800_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        listener.handlePasswordChanged(new PasswordChangedEvent("user-5"));

        verify(redisTemplate).delete(java.util.List.of(RedisKeys.LOGIN_LOCK + "user-5", RedisKeys.LOGIN_FAIL + "user-5"));
    }

    @Test
    @DisplayName("로그인 잠금 해제 실패는 삼킨다 - 토큰 무효화는 이미 끝났다")
    void handlePasswordChanged_잠금해제실패_미전파() {
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(1_800_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.delete(org.mockito.ArgumentMatchers.<java.util.Collection<String>>any()))
                .thenThrow(new RuntimeException("Redis 장애"));

        org.assertj.core.api.Assertions.assertThatCode(() -> listener.handlePasswordChanged(new PasswordChangedEvent("user-6")))
                .doesNotThrowAnyException();
        verify(valueOperations).set(eq(RedisKeys.PASSWORD_INVALIDATE + "user-6"), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("탈퇴 정리 중 예외 발생 시 전파하지 않음 — 나머지 리스너·purge가 막히지 않도록 격리 (M-S1-1)")
    void handleWithdrawn_정리_실패_예외_미전파() {
        org.mockito.Mockito.doThrow(new RuntimeException("Redis 장애"))
                .when(refreshTokenRepository).deleteByUserId("user-3");

        UserWithdrawnEvent event = new UserWithdrawnEvent("user-3", "127.0.0.1", "test-agent");

        org.assertj.core.api.Assertions.assertThatCode(() -> listener.handleWithdrawn(event))
                .doesNotThrowAnyException();
    }
}
