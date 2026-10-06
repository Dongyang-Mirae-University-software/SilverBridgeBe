package kr.silverbridge.main.global.security;

import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.util.RedisCounter;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Redis 기반 API 요청 속도 제한 서비스
 * 1분 고정 윈도우, 초과 시 TOO_MANY_REQUESTS(429) 반환
 *
 * <p>초과 시 {@link TooManyRequestsException}(남은 대기 시간 포함)을 던진다 - {@code CustomException} 하위라
 * 기존 호출처·핸들러 계약(429, TOO_MANY_REQUESTS)은 그대로다.
 *
 * <p><b>Redis 장애 시 fail-open (2026-10-02)</b>: 속도제한은 보조 방어라, Redis가 죽었다고 로그인·가입 전체를
 * 500으로 막지 않는다({@code [RATE-LIMIT-REDIS-DOWN]} WARN 후 통과). 이 예외는 이 클래스에만 둔다 -
 * 로그인 실패 잠금·인증번호 시도 횟수·SMS/메일 발송 상한은 {@link RedisCounter}를 직접 쓰며 장애 시
 * 그대로 실패한다(fail-closed). 그쪽까지 열면 Redis 장애가 곧 무제한 대입 허용이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisCounter redisCounter;

    private static final long WINDOW_SECONDS      = 60L;
    private static final long HOUR_WINDOW_SECONDS = 3600L;
    private static final int  MAX_REQUESTS        = 10;

    /**
     * 엔드포인트 + 식별자(IP 등) 기준 속도 제한 검사 (1분 고정 윈도우, 최대 10회)
     * 키 조합 형식은 서비스 내부에서 관리 (호출측 인라인 문자열 금지)
     *
     * @param endpoint   식별용 엔드포인트 이름 (예: "email-check", "pw-reset-sms")
     * @param identifier 식별자 (일반적으로 IP 주소)
     */
    public void check(String endpoint, String identifier) {
        String key = RedisKeys.RATE_LIMIT + endpoint + ":" + identifier;

        // INCR + 최초 TTL 설정을 원자적으로 (M-4)
        long count;
        try {
            count = redisCounter.incrementWithTtl(key, WINDOW_SECONDS);
        } catch (DataAccessException e) {
            logRedisDown(endpoint, e);
            return;
        }
        if (count > MAX_REQUESTS) {
            throw new TooManyRequestsException(retryAfter(key, WINDOW_SECONDS));
        }
    }

    /**
     * 최대 횟수를 호출자가 정하는 1분 고정 윈도우 검사 (2026-10-06, 로그인 사용자 기준 제한용).
     * 초과 시 {@link TooManyRequestsException}, Redis 장애 시 fail-open - 기본 {@link #check(String, String)}와 같다.
     *
     * @param maxPerMinute 1분 윈도우 최대 허용 횟수
     */
    public void check(String endpoint, String identifier, int maxPerMinute) {
        String key = RedisKeys.RATE_LIMIT + endpoint + ":" + identifier;

        long count;
        try {
            count = redisCounter.incrementWithTtl(key, WINDOW_SECONDS);
        } catch (DataAccessException e) {
            logRedisDown(endpoint, e);
            return;
        }
        if (count > maxPerMinute) {
            throw new TooManyRequestsException(retryAfter(key, WINDOW_SECONDS));
        }
    }

    /**
     * 분 + 시간 이중 윈도우 속도 제한 검사 (2026-05-23 추가).
     * <p>
     * 비밀번호 재설정처럼 <b>미가입 여부가 응답으로 노출되는</b> 엔드포인트의 자동화 enumeration·
     * 어뷰징을 분당·시간당 양쪽에서 막는다. 분 윈도우만으로는 IP당 분산 저빈도 스윕을 못 막으므로
     * 시간 윈도우를 함께 둔다. 기존 단일 윈도우 엔드포인트(signin 등)는 영향받지 않도록 별도 메서드로 둔다.
     * 두 카운터 모두 증가시킨 뒤 한쪽이라도 초과하면 429.
     *
     * @param endpoint     식별용 엔드포인트 이름 (예: "pw-reset-email")
     * @param identifier   식별자 (일반적으로 IP 주소)
     * @param maxPerMinute 1분 윈도우 최대 허용 횟수
     * @param maxPerHour   1시간 윈도우 최대 허용 횟수
     */
    public void check(String endpoint, String identifier, int maxPerMinute, int maxPerHour) {
        String minuteKey = RedisKeys.RATE_LIMIT + endpoint + ":1m:" + identifier;
        String hourKey   = RedisKeys.RATE_LIMIT + endpoint + ":1h:" + identifier;

        long perMinute;
        long perHour;
        try {
            perMinute = redisCounter.incrementWithTtl(minuteKey, WINDOW_SECONDS);
            perHour   = redisCounter.incrementWithTtl(hourKey, HOUR_WINDOW_SECONDS);
        } catch (DataAccessException e) {
            logRedisDown(endpoint, e);
            return;
        }

        boolean minuteExceeded = perMinute > maxPerMinute;
        boolean hourExceeded   = perHour > maxPerHour;
        if (minuteExceeded || hourExceeded) {
            // 둘 다 넘었으면 더 오래 기다려야 하는 쪽(시간 윈도우)을 안내한다
            long retryAfter = hourExceeded
                    ? retryAfter(hourKey, HOUR_WINDOW_SECONDS)
                    : retryAfter(minuteKey, WINDOW_SECONDS);
            throw new TooManyRequestsException(retryAfter);
        }
    }

    /** 남은 TTL(초). 읽지 못하면 윈도우 길이로 안내한다(짧게 안내해 곧바로 또 429가 나는 쪽을 피함). */
    private long retryAfter(String key, long windowSeconds) {
        try {
            long ttl = redisCounter.remainingTtlSeconds(key);
            if (ttl < 0) {
                return windowSeconds;
            }
            return Math.max(ttl, 1L);
        } catch (DataAccessException e) {
            return windowSeconds;
        }
    }

    // 식별자(IP·userId)는 남기지 않는다 - 장애 중에는 요청마다 찍혀 로그가 개인정보 저장소가 된다
    private void logRedisDown(String endpoint, DataAccessException e) {
        log.warn("[RATE-LIMIT-REDIS-DOWN] 속도제한 생략(fail-open) endpoint={} cause={}",
                endpoint, e.getClass().getSimpleName());
    }
}
