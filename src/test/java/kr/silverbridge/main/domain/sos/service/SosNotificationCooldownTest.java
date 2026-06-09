package kr.silverbridge.main.domain.sos.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
 * SosNotificationCooldown 단위 테스트.
 *
 * SET NX EX 결과에 따른 발송 허용/생략과, Redis 장애 시 긴급 우선 fail-open(발송 허용)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SosNotificationCooldownTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private SosNotificationCooldown cooldown;

    private static final String WARD_ID = "WD0001";
    private static final String KEY = "sos:notify:cooldown:" + WARD_ID;

    @Test
    @DisplayName("키 신규 설정(SET NX 성공) → 발송 허용(true) + 쿨다운 TTL로 시작")
    void tryAcquire_신규_허용() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), eq(SosNotificationCooldown.COOLDOWN))).thenReturn(true);

        assertThat(cooldown.tryAcquire(WARD_ID)).isTrue();
    }

    @Test
    @DisplayName("키 이미 존재(쿨다운 내) → 발송 생략(false)")
    void tryAcquire_쿨다운내_생략() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(cooldown.tryAcquire(WARD_ID)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 → 긴급 우선 fail-open으로 발송 허용(true)")
    void tryAcquire_Redis장애_failOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThat(cooldown.tryAcquire(WARD_ID)).isTrue();
    }
}
