package kr.silverbridge.main.global.security;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
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
     * @param key Redis 키 (RedisKeys.RATE_LIMIT + "{endpoint}:{identifier}")
     */
    public void check(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (count != null && count > MAX_REQUESTS) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }
}
