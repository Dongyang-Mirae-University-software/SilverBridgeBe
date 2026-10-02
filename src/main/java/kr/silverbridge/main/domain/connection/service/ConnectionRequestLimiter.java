package kr.silverbridge.main.domain.connection.service;

import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.util.RedisCounter;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 같은 (보호자, 피보호자) 쌍의 연결 요청 반복 제한 (CONN-G04 / XCUT-G29, 2026-10-02).
 *
 * <p>요청 → 취소(또는 거절)를 되풀이하면 요청마다 피보호자에게 연결 요청 알림이 새로 간다. 같은 쌍에 실제로
 * 만들어진 요청이 24시간 안에 {@value #MAX_REQUESTS_PER_PAIR}건이 되면, 다음 요청은 첫 요청 후 24시간이 지날 때까지
 * 429({@code CONNECTION_REQUEST_COOLDOWN})로 거절한다. 그 아래로는 지금처럼 자유롭게 다시 요청할 수 있다.
 * 거절 응답은 요청을 만들지 않으므로 피보호자에게 알림·WS가 가지 않는다.
 *
 * <p><b>판단 순서</b>: 생성 직전에 현재 건수를 읽어 한도면 거절 → 요청이 실제로 만들어진 뒤에 증가.
 * 원자적 "증가 후 비교"가 아니라서 같은 쌍의 동시 요청이 겹치면 한두 건 더 통과할 수 있는데, 괴롭힘을 줄이는
 * 보조 방어라 수용한다(같은 쌍의 PENDING은 uq_connections_live가 하나로 막으므로 동시 통과분도 대부분 409로 끝난다).
 *
 * <p><b>Redis 장애 시 fail-open</b>: {@code [CONN-REQUEST-LIMIT-REDIS-DOWN]} WARN만 남기고 통과시킨다. 이 제한이
 * 정상 연결 요청(가족이 처음 연결하는 경로)을 막으면 안 된다 - {@code RateLimitService}와 같은 판단이다.
 * 로그에는 식별자를 남기지 않는다(장애 중 요청마다 찍힌다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionRequestLimiter {

    // 이 건수만큼 만들어진 뒤의 요청을 막는다 - 5건까지는 자유, 6번째 요청부터 쿨다운(사용자 결정 "반복 5회 이상")
    static final int MAX_REQUESTS_PER_PAIR = 5;
    static final long WINDOW_SECONDS = 24 * 60 * 60L;

    private final StringRedisTemplate redisTemplate;
    private final RedisCounter redisCounter;

    /** 이 쌍이 이미 한도만큼 요청했으면 429. 요청을 만들기 직전에 부른다. */
    public void checkAllowed(String guardianId, String wardId) {
        String key = key(guardianId, wardId);
        long count;
        try {
            String value = redisTemplate.opsForValue().get(key);
            count = value == null ? 0L : Long.parseLong(value);
        } catch (DataAccessException e) {
            logRedisDown("check", e);
            return;
        } catch (NumberFormatException e) {
            // 우리가 쓰는 키라 생길 일은 없지만, 깨진 값 때문에 연결 요청이 500으로 막히지 않게 한다
            return;
        }
        if (count >= MAX_REQUESTS_PER_PAIR) {
            log.info("[CONN-REQUEST-COOLDOWN] 같은 쌍 반복 요청 차단: guardianId={}, wardId={}, count={}",
                    guardianId, wardId, count);
            throw new TooManyRequestsException(ErrorCode.CONNECTION_REQUEST_COOLDOWN, retryAfter(key));
        }
    }

    /** 요청이 실제로 만들어진 뒤 1 증가. 첫 증가 때 24시간 TTL이 붙는다(고정 윈도우). */
    public void recordRequest(String guardianId, String wardId) {
        try {
            redisCounter.incrementWithTtl(key(guardianId, wardId), WINDOW_SECONDS);
        } catch (DataAccessException e) {
            logRedisDown("record", e);
        }
    }

    /**
     * 피보호자가 수락해 ACTIVE가 되면 지운다. 피보호자가 받아들인 관계라 괴롭힘이 아니고, 남겨 두면 나중에 연결이
     * 끊긴 뒤 정상적인 재연결 요청이 그 전의 요청 횟수 때문에 막힌다.
     */
    public void reset(String guardianId, String wardId) {
        try {
            redisTemplate.delete(key(guardianId, wardId));
        } catch (DataAccessException e) {
            logRedisDown("reset", e);
        }
    }

    // 남은 TTL을 못 읽으면 윈도우 전체로 안내한다(짧게 안내해 곧바로 또 429가 나는 쪽을 피함)
    private long retryAfter(String key) {
        try {
            long ttl = redisCounter.remainingTtlSeconds(key);
            return ttl < 0 ? WINDOW_SECONDS : Math.max(ttl, 1L);
        } catch (DataAccessException e) {
            return WINDOW_SECONDS;
        }
    }

    private static String key(String guardianId, String wardId) {
        return RedisKeys.CONNECTION_REQUEST_COUNT + guardianId + ":" + wardId;
    }

    private void logRedisDown(String stage, DataAccessException e) {
        log.warn("[CONN-REQUEST-LIMIT-REDIS-DOWN] 연결 요청 반복 제한 생략(fail-open) stage={} cause={}",
                stage, e.getClass().getSimpleName());
    }
}
