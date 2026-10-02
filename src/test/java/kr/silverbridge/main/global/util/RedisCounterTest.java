package kr.silverbridge.main.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCounter - 남은 TTL 조회")
class RedisCounterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private RedisCounter redisCounter;

    @Test
    @DisplayName("남은 TTL(초)을 그대로 돌려준다")
    void remainingTtl() {
        when(redisTemplate.getExpire("k", TimeUnit.SECONDS)).thenReturn(37L);

        assertThat(redisCounter.remainingTtlSeconds("k")).isEqualTo(37L);
    }

    @Test
    @DisplayName("키 없음(-2)·만료 없음(-1)·null은 -1")
    void unknownTtl() {
        when(redisTemplate.getExpire("missing", TimeUnit.SECONDS)).thenReturn(-2L);
        when(redisTemplate.getExpire("persist", TimeUnit.SECONDS)).thenReturn(-1L);
        when(redisTemplate.getExpire("null", TimeUnit.SECONDS)).thenReturn(null);

        assertThat(redisCounter.remainingTtlSeconds("missing")).isEqualTo(-1L);
        assertThat(redisCounter.remainingTtlSeconds("persist")).isEqualTo(-1L);
        assertThat(redisCounter.remainingTtlSeconds("null")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Redis 장애는 삼키지 않는다 - 로그인 잠금·인증번호 시도 횟수 등 보안 카운터는 fail-closed")
    void redisDown_propagates() {
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> redisCounter.incrementWithTtl("k", 60L))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}
