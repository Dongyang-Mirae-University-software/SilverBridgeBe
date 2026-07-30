package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 이상감지 <b>알림</b> 쿨다운. 수신자 1명 단위로 동일 감지(같은 카메라·같은 종류)의 반복 알림을 억제한다.
 *
 * <p>이력 쿨다운({@link AnomalyEventCooldown})과 <b>별개</b>다 — 그쪽은 "이력을 몇 분에 한 번 적재할지",
 * 여기는 "수신자에게 몇 분에 한 번 알릴지"를 정한다. 수신자별로 간격이 다르기 때문에 하나로 합칠 수 없다:</p>
 *
 * <ul>
 *   <li><b>피보호자 본인 — 짧게(기본 3분)</b>. 화재 현장에 있는 당사자라 대피 재촉이 우선이다.
 *       다만 오탐 시 24시간 켜져 있는 화면에 알림이 쌓이므로 분 단위로 너무 잦지 않게 잡는다.</li>
 *   <li><b>보호자 — 길게(기본 5분)</b>. 여러 보호자에게 같은 알림이 반복되면 alarm fatigue로 이어지고,
 *       알림톡·SMS가 함께 나가 과금·카카오 신고 리스크도 커진다.</li>
 * </ul>
 *
 * <p>둘 다 <b>이력 쿨다운(기본 1분)보다 길다</b> — 이력은 촘촘히 남기되 사람에게는 성기게 알린다.</p>
 *
 * <p><b>fail-open</b>: Redis 장애로 확인이 실패하면 알림을 <b>막지 않는다</b>. 쿨다운 인프라 문제가 화재 알림을
 * 가로막아선 안 된다(SOS 쿨다운과 동일 원칙 — 차단보다 중복이 안전).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyNotificationCooldown {

    private static final String KEY_PREFIX = "anomaly:notify:";

    private final StringRedisTemplate redisTemplate;
    private final AnomalyProperties properties;

    /**
     * 이 수신자에게 알림을 보내도 되는지 원자적으로 판단하고, 가능하면 쿨다운을 시작한다(SET NX EX).
     *
     * @param userId       수신자(보호자 또는 피보호자 본인)
     * @param sessionId    감지된 카메라 SessionID
     * @param detectedType 감지 종류
     * @param self         수신자가 피보호자 본인이면 true (쿨다운을 짧게 적용)
     * @return 발송 가능하면 {@code true}. Redis 장애 시에도 {@code true}(fail-open).
     */
    public boolean tryAcquire(String userId, String sessionId, DetectedType detectedType, boolean self) {
        String key = KEY_PREFIX + userId + ":" + sessionId + ":" + detectedType.name();
        Duration cooldown = Duration.ofMinutes(self
                ? properties.getNotifySelfCooldownMinutes()
                : properties.getNotifyCooldownMinutes());
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", cooldown);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // 긴급 알림 우선: 쿨다운 인프라 장애가 화재 알림을 막지 않도록 fail-open
            log.warn("[ANOMALY] 알림 쿨다운 확인 실패 — 알림 강제 발송(fail-open): userId={}, error={}",
                    userId, e.getMessage());
            return true;
        }
    }
}
