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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AnomalyEventCooldown 해제(release) 단위 테스트 (ANOM-G14).
 */
@ExtendWith(MockitoExtension.class)
class AnomalyEventCooldownTest {

    private static final String SESSION_ID = "ward_a9cC5f_k3m";

    @Mock private StringRedisTemplate redisTemplate;

    private AnomalyEventCooldown cooldown;

    @BeforeEach
    void setUp() {
        AnomalyProperties properties = new AnomalyProperties();
        properties.setCooldownMinutes(1);
        cooldown = new AnomalyEventCooldown(redisTemplate, properties);
    }

    @Test
    @DisplayName("release는 선점과 같은 키를 지운다")
    void release_deletesSameKey() {
        cooldown.release(SESSION_ID, DetectedType.FIRE);

        verify(redisTemplate).delete("anomaly:cooldown:" + SESSION_ID + ":FIRE");
    }

    @Test
    @DisplayName("Redis 장애로 해제가 실패해도 예외를 던지지 않는다 (키는 TTL로 만료)")
    void release_swallowsRedisFailure() {
        when(redisTemplate.delete("anomaly:cooldown:" + SESSION_ID + ":FIRE"))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> cooldown.release(SESSION_ID, DetectedType.FIRE)).doesNotThrowAnyException();
    }
}
