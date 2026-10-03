package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.domain.camera.service.CameraStreamTicketService.StreamTicket;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영상 1회용 티켓 - 한 사용자·한 세션·60초·1회. 새어도 피해가 그 영상 1건으로 한정돼야 한다.
 */
@ExtendWith(MockitoExtension.class)
class CameraStreamTicketServiceTest {

    private static final String USER_ID = "GRD001";
    private static final String SESSION_ID = "ward_a9cC5f_live";
    private static final String VALID_TICKET = "st_" + "A".repeat(43);

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private CameraStreamTicketService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new CameraStreamTicketService(redisTemplate, new CameraStreamProperties());
    }

    private ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("예외가 나지 않았다");
    }

    @Test
    @DisplayName("발급: 형식에 맞는 티켓을 사용자·세션·발급시각과 함께 TTL 60초로 저장한다")
    void 발급() {
        String ticket = service.issue(USER_ID, SESSION_ID);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(key.capture(), value.capture(), eq(Duration.ofSeconds(60)));
        assertThat(ticket).matches("^st_[A-Za-z0-9_-]{43}$");
        assertThat(key.getValue()).isEqualTo(RedisKeys.CAMERA_STREAM_TICKET + ticket);
        assertThat(value.getValue()).startsWith(USER_ID + ":" + SESSION_ID + ":");
    }

    @Test
    @DisplayName("발급마다 다른 티켓이다(추측 불가)")
    void 발급_고유() {
        assertThat(service.issue(USER_ID, SESSION_ID)).isNotEqualTo(service.issue(USER_ID, SESSION_ID));
    }

    @Test
    @DisplayName("소비: GETDEL 한 번으로 꺼내 주인·발급 시각을 돌려준다(조회·삭제를 나누지 않는다)")
    void 소비_정상() {
        when(valueOps.getAndDelete(RedisKeys.CAMERA_STREAM_TICKET + VALID_TICKET))
                .thenReturn(USER_ID + ":" + SESSION_ID + ":1700000000000");

        StreamTicket ticket = service.consume(VALID_TICKET, SESSION_ID);

        assertThat(ticket.userId()).isEqualTo(USER_ID);
        assertThat(ticket.issuedAtMillis()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    @DisplayName("없음·만료·이미 사용한 티켓 → 401")
    void 소비_없는티켓_401() {
        when(valueOps.getAndDelete(anyString())).thenReturn(null);

        assertThat(errorOf(() -> service.consume(VALID_TICKET, SESSION_ID)))
                .isEqualTo(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
    }

    @Test
    @DisplayName("다른 카메라용 티켓 → 401 (티켓은 이미 소비돼 재사용도 불가)")
    void 소비_다른세션_401() {
        when(valueOps.getAndDelete(anyString())).thenReturn(USER_ID + ":ward_other:1700000000000");

        assertThat(errorOf(() -> service.consume(VALID_TICKET, SESSION_ID)))
                .isEqualTo(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
    }

    @Test
    @DisplayName("형식이 다른 값(빈 값·access token 등) → Redis를 조회하지 않고 401")
    void 소비_형식오류_401() {
        assertThat(errorOf(() -> service.consume(null, SESSION_ID))).isEqualTo(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        assertThat(errorOf(() -> service.consume("eyJhbGciOiJIUzI1NiJ9.x.y", SESSION_ID)))
                .isEqualTo(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        verify(valueOps, never()).getAndDelete(anyString());
    }

    @Test
    @DisplayName("Redis 장애 → 503 (401로 답하면 FE가 로그인 만료로 오해한다)")
    void 저장소장애_503() {
        when(valueOps.getAndDelete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(errorOf(() -> service.consume(VALID_TICKET, SESSION_ID))).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
