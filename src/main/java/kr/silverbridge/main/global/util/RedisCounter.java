package kr.silverbridge.main.global.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 카운터 증가 + TTL 설정을 원자적으로 수행하는 공통 유틸.
 * INCR 후 별도 EXPIRE를 호출하면 두 명령 사이에 장애 발생 시 TTL이 누락되어
 * 키가 영구히 남을 수 있다. Lua 스크립트로 한 번에 처리해 이 틈을 제거한다.
 */
@Component
@RequiredArgsConstructor
public class RedisCounter {

    private final StringRedisTemplate redisTemplate;

    // 첫 증가(결과가 1)일 때만 EXPIRE 설정 — 카운트 윈도우 시작 시점 기준 TTL
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    /**
     * 키를 1 증가시키고, 최초 증가일 때만 TTL을 설정한다(원자적).
     *
     * @param key        대상 Redis 키
     * @param ttlSeconds 최초 증가 시 설정할 만료 시간(초)
     * @return 증가 후 값 (실패 시 0)
     */
    public long incrementWithTtl(String key, long ttlSeconds) {
        Long count = redisTemplate.execute(INCR_WITH_TTL, List.of(key), String.valueOf(ttlSeconds));
        return count == null ? 0L : count;
    }
}
