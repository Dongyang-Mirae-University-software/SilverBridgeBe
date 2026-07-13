package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 이상감지 이력 적재 쿨다운. AI는 <b>매 프레임(초당 여러 번)</b> 분석 결과를 broadcast하므로, 위험 1건이
 * 이어지는 동안 같은 신호가 수백 번 도착한다. 쿨다운 없이는 이력 테이블이 사실상 동일 행으로 채워진다.
 *
 * <p>키는 {@code (sessionId, detectedType)} 단위다 — 같은 카메라에서 화재와 연기가 동시에 잡히면 서로 다른
 * 이력으로 남긴다(같은 종류의 반복만 억제).</p>
 *
 * <p><b>fail-open</b>: Redis 장애로 쿨다운 확인이 실패하면 적재를 <b>막지 않는다</b>. 중복 이력 몇 건이
 * 위험 이력 유실보다 안전하다(SOS 쿨다운과 동일한 원칙).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyEventCooldown {

    private static final String KEY_PREFIX = "anomaly:cooldown:";

    private final StringRedisTemplate redisTemplate;
    private final AnomalyProperties properties;

    /**
     * 이력을 적재해도 되는지 원자적으로 판단하고, 가능하면 쿨다운을 시작한다(SET NX EX).
     *
     * @return 적재 가능하면 {@code true}(쿨다운 시작), 쿨다운 내 중복이면 {@code false}(스킵).
     *         Redis 장애 시에는 {@code true}(fail-open).
     */
    public boolean tryAcquire(String sessionId, DetectedType detectedType) {
        String key = KEY_PREFIX + sessionId + ":" + detectedType.name();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofMinutes(properties.getCooldownMinutes()));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // 이력 유실보다 중복이 안전 — 쿨다운 인프라 장애가 위험 이력을 삼키지 않도록 fail-open
            log.warn("[ANOMALY] 쿨다운 확인 실패 — 이력 적재 강행(fail-open): sessionId={}, error={}",
                    sessionId, e.getMessage());
            return true;
        }
    }
}
