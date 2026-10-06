package kr.silverbridge.main.domain.sos.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.silverbridge.main.domain.sos.config.SosProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SosNotificationCooldown 단위 테스트.
 *
 * SET NX EX 결과에 따른 발송 허용/생략과, Redis 장애 시 긴급 우선 fail-open(발송 허용)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SosNotificationCooldownTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private SosNotificationCooldown cooldown;

    @BeforeEach
    void setUp() {
        cooldown = new SosNotificationCooldown(redisTemplate, new SosProperties());
    }

    private static final String WARD_ID = "WD0001";
    private static final String KEY = "sos:notify:cooldown:" + WARD_ID;

    @Test
    @DisplayName("키 신규 설정(SET NX 성공) → 발송 허용(true) + 쿨다운 TTL로 시작")
    void tryAcquire_신규_허용() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(KEY), eq("1"), eq(Duration.ofSeconds(10)))).thenReturn(true);

        assertThat(cooldown.tryAcquire(WARD_ID)).isTrue();
    }

    @Test
    @DisplayName("기본 쿨다운 TTL은 10초 - 9초 뒤 재요청 생략·11초 뒤 발송은 Redis EX 만료가 보장한다")
    void tryAcquire_기본_TTL_10초() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        cooldown.tryAcquire(WARD_ID);

        verify(valueOps).setIfAbsent(KEY, "1", Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("설정값이 TTL에 반영된다")
    void tryAcquire_설정값_반영() {
        SosProperties props = new SosProperties();
        props.setNotifyCooldownSeconds(45);
        SosNotificationCooldown custom = new SosNotificationCooldown(redisTemplate, props);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        custom.tryAcquire(WARD_ID);

        verify(valueOps).setIfAbsent(KEY, "1", Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("키 이미 존재(쿨다운 내) → 발송 생략(false)")
    void tryAcquire_쿨다운내_생략() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(cooldown.tryAcquire(WARD_ID)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 → 긴급 우선 fail-open으로 발송 허용(true)")
    void tryAcquire_Redis장애_failOpen() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThat(cooldown.tryAcquire(WARD_ID)).isTrue();
    }

    @Test
    @DisplayName("release → 쿨다운 키를 지운다 (아무에게도 못 보낸 SOS의 재요청이 다시 발송되게 - SOS-G09)")
    void release_키삭제() {
        cooldown.release(WARD_ID);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("장애 로그에는 예외 메시지 원문을 남기지 않는다 - 연결 주소·키 등이 섞일 수 있어 클래스명만 남긴다")
    void 장애_로그에_예외_원문_없음() {
        Logger logger = (Logger) LoggerFactory.getLogger(SosNotificationCooldown.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("SECRET-REDIS-HOST:6379 unreachable"));
            when(redisTemplate.delete(KEY)).thenThrow(new IllegalStateException("SECRET-KEY-DETAIL"));

            cooldown.tryAcquire(WARD_ID);
            cooldown.release(WARD_ID);

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("SECRET")
                    .contains(WARD_ID));
            assertThat(appender.list.get(0).getFormattedMessage()).contains("RuntimeException");
            assertThat(appender.list.get(1).getFormattedMessage()).contains("IllegalStateException");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("release 중 Redis 장애 → 예외를 삼킨다(키는 TTL로 자동 만료)")
    void release_Redis장애_삼킴() {
        when(redisTemplate.delete(KEY)).thenThrow(new RuntimeException("redis down"));

        assertThatNoException().isThrownBy(() -> cooldown.release(WARD_ID));
    }
}
