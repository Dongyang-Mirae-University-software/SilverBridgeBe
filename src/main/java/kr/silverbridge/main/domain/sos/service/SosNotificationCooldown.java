package kr.silverbridge.main.domain.sos.service;

import kr.silverbridge.main.domain.sos.config.SosProperties;
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
public class SosNotificationCooldown {

    private static final String KEY_PREFIX = "sos:notify:cooldown:";

    private final StringRedisTemplate redisTemplate;

    /** 동일 피보호자에 대한 SOS 알림 발송 최소 간격(기본 10초). 이 안에서의 재요청은 알림을 생략한다(이력은 보존). */
    private final Duration cooldown;

    public SosNotificationCooldown(StringRedisTemplate redisTemplate, SosProperties properties) {
        this.redisTemplate = redisTemplate;
        this.cooldown = Duration.ofSeconds(properties.effectiveNotifyCooldownSeconds());
    }

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
                    .setIfAbsent(KEY_PREFIX + wardId, "1", cooldown);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // 긴급 알림 우선: 쿨다운 인프라 장애가 SOS 알림을 막지 않도록 fail-open
            log.warn("SOS 쿨다운 확인 실패 — 알림 강제 발송(fail-open): wardId={}, exception={}", wardId, e.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * 선점한 쿨다운을 푼다 - 이번 발송이 <b>아무에게도 나가지 못했을 때</b>만 부른다(SOS-G09).
     *
     * <p>쿨다운은 "선점 후 발송"이라 발송 전에 키를 건다. 발송이 전부 실패했는데 키가 남으면 쿨다운 안의 재요청이
     * "직전에 보냈다"는 이유로 알림을 생략해, 다시 알릴 가장 필요한 순간에 알림이 빠진다. 쿨다운의 목적은 같은
     * 알림의 폭주를 막는 것이지 실패한 알림을 재시도하지 못하게 하는 것이 아니다.</p>
     *
     * <p>해제 실패는 삼킨다 - 키는 쿨다운이 지나면 저절로 만료되므로 최악이어도 지금과 같다.</p>
     *
     * @param wardId SOS를 발생시킨 피보호자 ID
     */
    public void release(String wardId) {
        try {
            redisTemplate.delete(KEY_PREFIX + wardId);
        } catch (Exception e) {
            log.warn("SOS 쿨다운 해제 실패 — {}초 뒤 자동 만료: wardId={}, exception={}",
                    cooldown.toSeconds(), wardId, e.getClass().getSimpleName());
        }
    }
}
