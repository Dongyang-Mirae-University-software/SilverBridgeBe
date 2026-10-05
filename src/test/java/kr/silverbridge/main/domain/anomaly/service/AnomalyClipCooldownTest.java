package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 클립 쿨다운 - 이력·알림 쿨다운과 별개 키, Redis 장애 시 <b>fail-closed</b>(2026-10-05 QA 종합 점검 테스트 공백 보강).
 *
 * <p>이력·알림 쿨다운은 장애 시 fail-open(위험 신호를 삼키지 않는다)이지만 클립은 부가 기능이라 반대다 - 열어 두면 장애 동안
 * 감지마다 AI 인코딩을 요청한다. 누가 "다른 쿨다운과 맞추자"며 fail-open으로 바꾸면 여기서 깨진다.</p>
 */
@ExtendWith(MockitoExtension.class)
class AnomalyClipCooldownTest {

    private static final String SESSION = "ward_k3m9Q2aZ7pLx01Bc";
    private static final String KEY = "anomaly:clip:" + SESSION + ":FIRE";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private AnomalyClipCooldown cooldown;

    @BeforeEach
    void setUp() {
        AnomalyProperties properties = new AnomalyProperties();
        properties.getClip().setCooldownMinutes(5);
        cooldown = new AnomalyClipCooldown(redisTemplate, properties);
    }

    @Test
    @DisplayName("비어 있으면 설정한 분(5분) TTL로 키를 잡고 true - 키는 세션·유형별이다")
    void acquire() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(KEY, "1", Duration.ofMinutes(5))).thenReturn(true);

        assertThat(cooldown.tryAcquire(SESSION, DetectedType.FIRE)).isTrue();
    }

    @Test
    @DisplayName("이미 잡혀 있으면 false")
    void alreadyHeld() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), any(Duration.class))).thenReturn(false);

        assertThat(cooldown.tryAcquire(SESSION, DetectedType.FIRE)).isFalse();
    }

    @Test
    @DisplayName("Redis가 null을 돌려주면(파이프라인·트랜잭션 등) 잡지 못한 것으로 본다")
    void nullIsNotAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(cooldown.tryAcquire(SESSION, DetectedType.FIRE)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애면 fail-closed - 예외 없이 false(클립 생략), 이력·알림 쿨다운과 반대")
    void redisDownIsFailClosed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cooldown.tryAcquire(SESSION, DetectedType.FIRE)).isFalse();
    }

    @Test
    @DisplayName("해제하면 같은 키를 지운다")
    void release() {
        cooldown.release(SESSION, DetectedType.FIRE);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("해제 중 Redis 장애는 삼킨다 - TTL로 사라지고, 호출부(캡처 finally)를 깨지 않는다")
    void releaseFailureIsSwallowed() {
        when(redisTemplate.delete(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatNoException().isThrownBy(() -> cooldown.release(SESSION, DetectedType.FIRE));
    }

    @Test
    @DisplayName("유형이 다르면 키가 다르다 - 화재 클립 쿨다운이 흉기 클립을 막지 않는다")
    void keyPerType() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("anomaly:clip:" + SESSION + ":WEAPON"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        assertThat(cooldown.tryAcquire(SESSION, DetectedType.WEAPON)).isTrue();
    }
}
