package kr.silverbridge.main.global.security;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisCounter;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Redis 기반 API 요청 속도 제한 서비스
 * 1분 고정 윈도우, 초과 시 TOO_MANY_REQUESTS(429) 반환
 */
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
        long count = redisCounter.incrementWithTtl(key, WINDOW_SECONDS);
        if (count > MAX_REQUESTS) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
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

        long perMinute = redisCounter.incrementWithTtl(minuteKey, WINDOW_SECONDS);
        long perHour   = redisCounter.incrementWithTtl(hourKey, HOUR_WINDOW_SECONDS);

        if (perMinute > maxPerMinute || perHour > maxPerHour) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
