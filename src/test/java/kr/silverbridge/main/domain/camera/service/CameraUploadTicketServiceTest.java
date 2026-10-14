package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.service.CameraUploadTicketService.UploadTicket;
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
 * 송출 업로드 티켓 - 본인 카메라 한 세션·60초·1회. 시청 티켓(st_)과 형식·키가 모두 달라 서로 소비할 수 없어야 한다.
 */
@ExtendWith(MockitoExtension.class)
class CameraUploadTicketServiceTest {

    private static final String WARD_ID = "WD0001";
    private static final String SESSION_ID = "ward_a9cC5f_live";
    private static final String UPLOAD_TICKET = "ut_" + "A".repeat(43);
    private static final String STREAM_TICKET = "st_" + "A".repeat(43);

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private CameraUploadTicketService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new CameraUploadTicketService(redisTemplate, new CameraUploadProperties());
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
    @DisplayName("발급: ut_ 형식 티켓을 피보호자·세션·발급시각과 함께 업로드 전용 키에 TTL 60초로 저장한다")
    void 발급() {
        String ticket = service.issue(WARD_ID, SESSION_ID);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(key.capture(), value.capture(), eq(Duration.ofSeconds(60)));
        assertThat(ticket).matches("^ut_[A-Za-z0-9_-]{43}$");
        assertThat(key.getValue()).isEqualTo(RedisKeys.CAMERA_UPLOAD_TICKET + ticket);
        assertThat(key.getValue()).doesNotStartWith(RedisKeys.CAMERA_STREAM_TICKET);
        assertThat(value.getValue()).startsWith(WARD_ID + ":" + SESSION_ID + ":");
    }

    @Test
    @DisplayName("소비: GETDEL 한 번으로 꺼내 피보호자·세션·발급 시각을 돌려준다")
    void 소비_정상() {
        when(valueOps.getAndDelete(RedisKeys.CAMERA_UPLOAD_TICKET + UPLOAD_TICKET))
                .thenReturn(WARD_ID + ":" + SESSION_ID + ":1760400000000");

        UploadTicket ticket = service.consume(UPLOAD_TICKET);

        assertThat(ticket).isEqualTo(new UploadTicket(WARD_ID, SESSION_ID, 1760400000000L));
        verify(valueOps, never()).get(anyString());   // 조회와 삭제를 나누면 동시에 두 연결이 열릴 수 있다
    }

    @Test
    @DisplayName("소비: 이미 쓴·만료된 티켓은 401")
    void 소비_없음() {
        when(valueOps.getAndDelete(anyString())).thenReturn(null);
        assertThat(errorOf(() -> service.consume(UPLOAD_TICKET))).isEqualTo(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
    }

    @Test
    @DisplayName("시청 티켓(st_)으로는 송출을 열 수 없다 - 형식에서 거절하고 Redis를 조회하지 않는다")
    void 시청_티켓_소비_불가() {
        assertThat(errorOf(() -> service.consume(STREAM_TICKET))).isEqualTo(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        verify(valueOps, never()).getAndDelete(anyString());
    }

    @Test
    @DisplayName("반대 방향도 막는다 - 송출 티켓(ut_)은 시청 티켓 서비스에서 형식 단계에서 거절된다")
    void 송출_티켓으로_시청_불가() {
        CameraStreamTicketService streamService = new CameraStreamTicketService(redisTemplate, new CameraStreamProperties());
        assertThat(errorOf(() -> streamService.consume(UPLOAD_TICKET, SESSION_ID)))
                .isEqualTo(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
        verify(valueOps, never()).getAndDelete(anyString());
    }

    @Test
    @DisplayName("형식이 틀린 티켓(null·짧음·접두사 없음·문자 오류)은 Redis를 조회하지 않고 401")
    void 형식_오류() {
        for (String bad : new String[] {null, "", "ut_short", "A".repeat(46), "ut_" + "A".repeat(42) + "!", "ut_" + "A".repeat(44)}) {
            assertThat(errorOf(() -> service.consume(bad))).isEqualTo(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
        }
        verify(valueOps, never()).getAndDelete(anyString());
    }

    @Test
    @DisplayName("저장값 형식이 깨졌으면 거부(소비는 이미 됐다)")
    void 저장값_오류() {
        when(valueOps.getAndDelete(anyString())).thenReturn("broken-value");
        assertThat(errorOf(() -> service.consume(UPLOAD_TICKET))).isEqualTo(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);

        when(valueOps.getAndDelete(anyString())).thenReturn(WARD_ID + ":" + SESSION_ID + ":not-a-number");
        assertThat(errorOf(() -> service.consume(UPLOAD_TICKET))).isEqualTo(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
    }

    @Test
    @DisplayName("저장소 장애: 발급·소비 모두 fail-closed 503(통과시키지 않는다)")
    void 저장소_장애() {
        when(valueOps.getAndDelete(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(errorOf(() -> service.consume(UPLOAD_TICKET))).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        assertThat(errorOf(() -> service.issue(WARD_ID, SESSION_ID))).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
