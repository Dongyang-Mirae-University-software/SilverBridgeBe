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
 * <p>키는 {@code (sessionId, detectedType)} 단위다 - 같은 종류의 반복만 억제한다. 연기는 화재로 받으므로
 * (2026-09-21) 같은 카메라의 화재·연기 감지는 한 키를 공유한다.</p>
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

    /**
     * 선점한 쿨다운을 되돌린다 - 이력을 남기지 못했을 때(소유자 매핑 실패·저장 실패·롤백)만 부른다(ANOM-G14).
     *
     * <p>선점은 그대로 저장 <b>전</b>에 한다 - 매 프레임 동시에 도착하는 같은 신호 중 하나만 통과시키는 것이
     * 쿨다운의 목적이라, 저장 뒤로 미루면 그 사이 도착한 프레임이 모두 중복 이력이 된다. 대신 실패하면 키를 지워
     * 다음 프레임이 곧바로 다시 시도하게 한다. 지우지 않으면 일시 장애 직후 쿨다운 동안의 화재 신호가 모두 버려진다.</p>
     *
     * <p>Redis 오류는 삼킨다 - 호출자는 이미 실패 처리 중이고, 남은 키는 TTL로 저절로 사라진다.</p>
     */
    public void release(String sessionId, DetectedType detectedType) {
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId + ":" + detectedType.name());
        } catch (Exception e) {
            log.warn("[ANOMALY] 쿨다운 해제 실패 — TTL 만료까지 유지: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }
}
