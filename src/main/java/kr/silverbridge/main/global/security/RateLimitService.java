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

    private static final long WINDOW_SECONDS = 60L;
    private static final int  MAX_REQUESTS   = 10;

    /**
     * 엔드포인트 + 식별자(IP 등) 기준 속도 제한 검사
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
}
