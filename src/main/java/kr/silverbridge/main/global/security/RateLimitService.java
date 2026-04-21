package kr.silverbridge.main.global.security;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 API 요청 속도 제한 서비스
 * 1분 슬라이딩 윈도우, 초과 시 TOO_MANY_REQUESTS(429) 반환
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

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

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (count != null && count > MAX_REQUESTS) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
