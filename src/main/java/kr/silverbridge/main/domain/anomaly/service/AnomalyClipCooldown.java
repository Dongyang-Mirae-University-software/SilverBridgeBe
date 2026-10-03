package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.global.enums.DetectedType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 이상감지 <b>클립</b> 쿨다운 - 같은 {@code (sessionId, detectedType)}의 클립을 몇 분에 한 번 만들지 정한다.
 *
 * <p>이력 쿨다운({@link AnomalyEventCooldown})·알림 쿨다운({@link AnomalyNotificationCooldown})과 <b>키·설정이 별개</b>다 -
 * 알림 빈도를 바꿀 때 디스크·AI 인코딩 부하가 함께 흔들리지 않게 한다.</p>
 *
 * <p><b>fail-closed</b>: Redis 장애로 확인이 실패하면 클립을 <b>만들지 않는다</b>. 이력·알림 쿨다운의 fail-open과 반대인데,
 * 그쪽은 위험 신호를 삼키지 않기 위해서고 클립은 부가 기능이다. 열어 두면 장애 동안 감지마다(1분 간격) AI 인코딩을
 * 요청하게 된다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyClipCooldown {

    private static final String KEY_PREFIX = "anomaly:clip:";

    private final StringRedisTemplate redisTemplate;
    private final AnomalyProperties properties;

    /** 클립을 만들어도 되면 쿨다운을 시작하고 true. 쿨다운 중이거나 Redis 장애면 false. */
    public boolean tryAcquire(String sessionId, DetectedType detectedType) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(sessionId, detectedType), "1",
                    Duration.ofMinutes(properties.getClip().getCooldownMinutes()));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("[ANOMALY-CLIP] 쿨다운 확인 실패 - 클립 생략(fail-closed): sessionId={}, error={}",
                    sessionId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** 클립을 만들지 못했을 때 쿨다운을 풀어 다음 감지에서 다시 시도하게 한다. Redis 오류는 삼킨다(TTL로 사라진다). */
    public void release(String sessionId, DetectedType detectedType) {
        try {
            redisTemplate.delete(key(sessionId, detectedType));
        } catch (Exception e) {
            log.warn("[ANOMALY-CLIP] 쿨다운 해제 실패 - TTL 만료까지 유지: sessionId={}, error={}",
                    sessionId, e.getClass().getSimpleName());
        }
    }

    private static String key(String sessionId, DetectedType detectedType) {
        return KEY_PREFIX + sessionId + ":" + detectedType.name();
    }
}
