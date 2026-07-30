package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AnomalyNotificationCooldown 단위 테스트 (2026-07-14 점검 L-3).
 *
 * 검증: 수신자별 TTL 분기(본인 1분 / 보호자 5분), 쿨다운 내 재발송 차단, Redis 장애 시 fail-open.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyNotificationCooldownTest {

    private static final String SESSION_ID = "ward_a9cC5f_k3m";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private AnomalyNotificationCooldown cooldown;

    @BeforeEach
    void setUp() {
        AnomalyProperties properties = new AnomalyProperties();
        properties.setNotifyCooldownMinutes(5);
        properties.setNotifySelfCooldownMinutes(3);
        cooldown = new AnomalyNotificationCooldown(redisTemplate, properties);
    }

    @Test
    @DisplayName("보호자는 5분, 피보호자 본인은 3분 쿨다운을 적용한다(현장 당사자라 더 자주 알린다)")
    void 수신자별_TTL() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        cooldown.tryAcquire("GD0001", SESSION_ID, DetectedType.FIRE, false);
        cooldown.tryAcquire("WD0001", SESSION_ID, DetectedType.FIRE, true);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        org.mockito.Mockito.verify(valueOperations, org.mockito.Mockito.times(2))
                .setIfAbsent(anyString(), eq("1"), ttl.capture());
        assertThat(ttl.getAllValues()).containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("쿨다운 내 재발송은 차단한다(키가 이미 있으면 false)")
    void 쿨다운내_차단() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        assertThat(cooldown.tryAcquire("GD0001", SESSION_ID, DetectedType.FIRE, false)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시에는 알림을 막지 않는다(fail-open — 화재 알림이 인프라 문제로 죽으면 안 된다)")
    void redis장애_failOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        assertThat(cooldown.tryAcquire("GD0001", SESSION_ID, DetectedType.FIRE, false)).isTrue();
    }
}
