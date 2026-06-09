package kr.silverbridge.main.domain.sos.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * SOS 긴급 알림 쿨다운. 피보호자가 짧은 시간에 SOS를 연타할 때 보호자에게 동일 알림이 폭주(alarm fatigue)하는 것을
 * 막는다.
 *
 * <p><b>이력과 무관</b>: 쿨다운은 <b>알림 발송</b>에만 적용된다. {@code sos_events} 이력은 항상 저장되므로
 * 연타도 전부 기록에 남는다 — "이력은 무조건 남는다"는 SOS 원칙을 깨지 않는다.</p>
 *
 * <p><b>긴급 우선(fail-open)</b>: Redis 장애 등으로 쿨다운 확인 자체가 실패하면 알림을 <b>막지 않고 발송</b>한다.
 * 쿨다운 인프라 문제가 생명 관련 알림을 가로막아선 안 되기 때문이다(차단보다 중복이 안전).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SosNotificationCooldown {

    /** 동일 피보호자에 대한 SOS 알림 발송 최소 간격. 이 안에서의 재요청은 알림을 생략한다(이력은 보존). */
    static final Duration COOLDOWN = Duration.ofSeconds(30);
    private static final String KEY_PREFIX = "sos:notify:cooldown:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 알림을 발송해도 되는지 원자적으로 판단하고, 발송 가능하면 쿨다운을 시작한다(SET NX EX).
     *
     * @param wardId SOS를 발생시킨 피보호자 ID
     * @return 발송 가능하면 {@code true}(쿨다운 시작), 직전 발송 후 쿨다운 내면 {@code false}(알림 생략).
     *         Redis 장애 시에는 긴급 우선 원칙에 따라 {@code true}(fail-open).
     */
    public boolean tryAcquire(String wardId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + wardId, "1", COOLDOWN);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // 긴급 알림 우선: 쿨다운 인프라 장애가 SOS 알림을 막지 않도록 fail-open
            log.warn("SOS 쿨다운 확인 실패 — 알림 강제 발송(fail-open): wardId={}, error={}", wardId, e.getMessage());
            return true;
        }
    }
}
