package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 로그인으로 밀려난 refresh와 회전 재사용의 구분 (AUTH-G03, 결정 D3). */
@ExtendWith(MockitoExtension.class)
class LoginSupersedeMarkerTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private JwtTokenProvider jwtTokenProvider;

    @InjectMocks private LoginSupersedeMarker marker;

    private static final String USER_ID = "abc123";

    @Test
    @DisplayName("로그인 시 새 refresh의 iat(초)를 그 토큰 수명만큼 기록한다")
    void markLogin_iat초_기록() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jwtTokenProvider.getRemainingExpiration("new-refresh")).thenReturn(604_800_000L);
        when(jwtTokenProvider.getIssuedAt("new-refresh")).thenReturn(1_700_000_123_000L);

        marker.markLogin(USER_ID, "new-refresh");

        verify(valueOperations).set(RedisKeys.REFRESH_LOGIN_AT + USER_ID, "1700000123", 604_800_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("마지막 로그인보다 먼저 발급된 토큰만 '밀려난 토큰' - 같은 초·이후 발급(회전분)은 재사용 감지 대상으로 남는다")
    void isIssuedBeforeLastLogin_경계() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.REFRESH_LOGIN_AT + USER_ID)).thenReturn("1700000123");
        when(jwtTokenProvider.getIssuedAt("older")).thenReturn(1_700_000_122_999L);
        when(jwtTokenProvider.getIssuedAt("same-second")).thenReturn(1_700_000_123_500L);
        when(jwtTokenProvider.getIssuedAt("rotated-after")).thenReturn(1_700_000_200_000L);

        assertThat(marker.isIssuedBeforeLastLogin(USER_ID, "older")).isTrue();
        assertThat(marker.isIssuedBeforeLastLogin(USER_ID, "same-second")).isFalse();
        assertThat(marker.isIssuedBeforeLastLogin(USER_ID, "rotated-after")).isFalse();
    }

    @Test
    @DisplayName("기록이 없거나 Redis 오류면 false - 기존 재사용 감지(H-3)로 간다")
    void isIssuedBeforeLastLogin_기록없음_오류() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        assertThat(marker.isIssuedBeforeLastLogin(USER_ID, "token")).isFalse();

        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(marker.isIssuedBeforeLastLogin(USER_ID, "token")).isFalse();
    }

    @Test
    @DisplayName("기록 실패는 로그인을 막지 않는다")
    void markLogin_Redis오류_삼킴() {
        when(jwtTokenProvider.getRemainingExpiration("new-refresh")).thenReturn(1000L);
        when(jwtTokenProvider.getIssuedAt("new-refresh")).thenReturn(1_000L);
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> marker.markLogin(USER_ID, "new-refresh")).doesNotThrowAnyException();
    }
}
