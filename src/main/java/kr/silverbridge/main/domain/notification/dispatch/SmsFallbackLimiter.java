package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.config.SmsFallbackProperties;
import kr.silverbridge.main.global.util.RedisCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 필수 알림 문자 폴백의 시간당 상한. 수신자·알림 종류별 고정 1시간 창 카운터(Redis)다.
 *
 * <p><b>문자 폴백에만 건다</b> - 푸시·WebSocket·이력·쿨다운은 건드리지 않는다. 상한을 넘으면 그 문자만 생략되고
 * 이력에는 {@code RATE_LIMITED}로 남는다. 알림 자체를 막는 429가 아니다.</p>
 *
 * <p><b>실패한 발송은 환불한다</b>({@link #release}) - 문자가 접수되지 않았으면(번호 없음·발송사 오류) 한 건을 돌려준다.
 * 안 그러면 발송사 장애 중 연타가 한도를 다 써서 복구 뒤에도 최대 1시간 문자가 막히고, 번호가 없는 보호자도 한도를 쓴다.</p>
 *
 * <p><b>긴급 우선(fail-open)</b>: Redis 장애로 세지 못하면 막지 않고 보낸다(차단보다 중복이 안전).</p>
 *
 * <p>키는 서버가 정한 {@code 종류 + 수신자 ID}다. 디스패처의 wardId는 이력 표시 전용이라 쓰지 않는다.
 * 창을 고정으로 둔 탓에 경계에서 최대 2배까지 나갈 수 있음을 수용한다.</p>
 */
@Slf4j
@Component
public class SmsFallbackLimiter {

    private static final String KEY_PREFIX = "notify:sms-fallback:";
    private static final long WINDOW_SECONDS = 3600;

    // 환불 - 키가 있고 0보다 클 때만 DECR(TTL 유지). 창이 이미 만료됐다면 아무것도 하지 않아 TTL 없는 키를 만들지 않는다.
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "local c = tonumber(redis.call('GET', KEYS[1])) "
                    + "if c and c > 0 then return redis.call('DECR', KEYS[1]) end "
                    + "return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisCounter redisCounter;
    private final int maxPerHour;

    public SmsFallbackLimiter(StringRedisTemplate redisTemplate, RedisCounter redisCounter,
                              SmsFallbackProperties properties) {
        this.redisTemplate = redisTemplate;
        this.redisCounter = redisCounter;
        this.maxPerHour = properties.effectiveMaxPerHour();
    }

    /**
     * 문자 폴백을 보내도 되는지 판단하고, 보내는 것으로 한 건을 센다(선점 후 발송). 접수되지 않으면 {@link #release}로 돌려준다.
     *
     * @return 보내도 되면 {@code true}. 상한이 꺼져 있거나(0) Redis 장애면 항상 {@code true}
     */
    public boolean tryAcquire(NotificationType type, String recipientId) {
        if (maxPerHour == 0) {
            return true;
        }
        String key = KEY_PREFIX + type.name() + ":" + recipientId;
        try {
            // INCR과 최초 TTL 설정을 Lua 한 번으로 - 따로 부르면 그 사이 창이 만료돼 TTL 없는 키가 남을 수 있다.
            long count = redisCounter.incrementWithTtl(key, WINDOW_SECONDS);
            return count == 0 || count <= maxPerHour; // 0 = 값을 읽지 못함 → 막지 않는다
        } catch (Exception e) {
            log.warn("[SMS-FALLBACK-CAP-REDIS-DOWN] 문자 폴백 상한 확인 실패 - 막지 않고 발송(fail-open): type={}, cause={}",
                    type, e.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * 센 한 건을 돌려준다 - 문자가 접수되지 않았을 때 부른다. 실패는 삼킨다(최악이어도 이전 동작과 같다).
     * 시간 초과 뒤 늦게 접수된 건은 환불되어 한도를 약간 넘길 수 있음을 수용한다.
     */
    public void release(NotificationType type, String recipientId) {
        if (maxPerHour == 0) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE, List.of(KEY_PREFIX + type.name() + ":" + recipientId));
        } catch (DataAccessException e) {
            log.warn("[SMS-FALLBACK-CAP-RELEASE-FAILED] 문자 폴백 상한 환불 실패 cause={}", e.getClass().getSimpleName());
        }
    }
}
