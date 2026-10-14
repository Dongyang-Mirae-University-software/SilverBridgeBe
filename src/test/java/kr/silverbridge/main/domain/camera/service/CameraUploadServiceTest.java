package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.dto.UploadTicketResponse;
import kr.silverbridge.main.domain.camera.service.CameraUploadService.UploadGrant;
import kr.silverbridge.main.domain.camera.service.CameraUploadTicketService.UploadTicket;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 송출 WebSocket 인가 - 발급·핸드셰이크 소비·주기 재확인. 피보호자 본인 카메라만, {@code is_active}는 보지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class CameraUploadServiceTest {

    private static final String WARD_ID = "WD0001";
    private static final String SESSION_ID = "ward_a9cC5f_live";
    private static final long CAMERA_ID = 7L;
    private static final String TICKET = "ut_" + "A".repeat(43);

    @Mock private CameraService cameraService;
    @Mock private CameraUploadTicketService ticketService;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private RateLimitService rateLimitService;

    private CameraUploadProperties properties;
    private CameraUploadService service;

    @BeforeEach
    void setUp() {
        properties = new CameraUploadProperties();
        properties.setWsUrl("wss://api.example/ws/camera-upload");
        properties.setWsH264SessionIds(SESSION_ID);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(userRepository.findStatusById(WARD_ID)).thenReturn(Optional.of(Status.ACTIVE));
        lenient().when(cameraService.isOwnedSession(WARD_ID, SESSION_ID)).thenReturn(true);
        lenient().when(cameraService.getOwnedSessionId(WARD_ID, CAMERA_ID)).thenReturn(SESSION_ID);
        service = new CameraUploadService(cameraService, ticketService, properties, userRepository, redisTemplate,
                rateLimitService);
    }

    private ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("예외가 나지 않았다");
    }

    // ---- 발급 ----

    @Test
    @DisplayName("발급: 속도 제한 -> 소유 확인 -> 티켓 순서로, 응답에는 공개 wss 주소만 싣는다")
    void 발급_정상() {
        when(ticketService.issue(WARD_ID, SESSION_ID)).thenReturn(TICKET);

        UploadTicketResponse response = service.issueTicket(WARD_ID, CAMERA_ID);

        assertThat(response.ticket()).isEqualTo(TICKET);
        assertThat(response.expiresInSeconds()).isEqualTo(60);
        assertThat(response.uploadUrl()).isEqualTo("wss://api.example/ws/camera-upload");
        InOrder order = inOrder(rateLimitService, cameraService, ticketService);
        order.verify(rateLimitService).check("camera-upload-ticket", WARD_ID, 20, 300);
        order.verify(cameraService).getOwnedSessionId(WARD_ID, CAMERA_ID);
        order.verify(ticketService).issue(WARD_ID, SESSION_ID);
    }

    @Test
    @DisplayName("발급: 타인 카메라 예외는 그대로 전파되고 티켓을 만들지 않는다(403 + IDOR 로그는 CameraService 몫)")
    void 발급_타인_카메라() {
        when(cameraService.getOwnedSessionId(WARD_ID, CAMERA_ID))
                .thenThrow(new CustomException(ErrorCode.CAMERA_NOT_AUTHORIZED));

        assertThat(errorOf(() -> service.issueTicket(WARD_ID, CAMERA_ID))).isEqualTo(ErrorCode.CAMERA_NOT_AUTHORIZED);
        verifyNoInteractions(ticketService);
    }

    @Test
    @DisplayName("발급: 플래그 목록에 없는 카메라는 409 - jpeg 카메라에 자격을 만들지 않는다")
    void 발급_플래그_없음() {
        properties.setWsH264SessionIds("ward_other");
        assertThat(errorOf(() -> service.issueTicket(WARD_ID, CAMERA_ID))).isEqualTo(ErrorCode.CAMERA_UPLOAD_NOT_ENABLED);

        properties.setWsH264SessionIds("");
        assertThat(errorOf(() -> service.issueTicket(WARD_ID, CAMERA_ID))).isEqualTo(ErrorCode.CAMERA_UPLOAD_NOT_ENABLED);
        verifyNoInteractions(ticketService);
    }

    @Test
    @DisplayName("발급: 킬 스위치가 꺼졌거나 공개 주소가 비어 있으면 503(소유 확인 전에 거절)")
    void 발급_기능_꺼짐() {
        properties.setTicketEnabled(false);
        assertThat(errorOf(() -> service.issueTicket(WARD_ID, CAMERA_ID))).isEqualTo(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);

        properties.setTicketEnabled(true);
        properties.setWsUrl(" ");
        assertThat(errorOf(() -> service.issueTicket(WARD_ID, CAMERA_ID))).isEqualTo(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        verifyNoInteractions(cameraService, ticketService);
    }

    @Test
    @DisplayName("발급: 카메라가 꺼져(is_active=false) 있어도 발급된다 - 소유 확인만 한다")
    void 발급_비활성_카메라도_가능() {
        when(ticketService.issue(WARD_ID, SESSION_ID)).thenReturn(TICKET);
        service.issueTicket(WARD_ID, CAMERA_ID);
        // 시청 인가 메서드(활성 카메라만 통과)는 부르지 않는다
        verify(cameraService, never()).getViewableCamera(anyString(), anyString());
        verify(cameraService, never()).isViewable(anyString(), anyString());
    }

    // ---- 핸드셰이크 인가 ----

    @Test
    @DisplayName("인가: 정상이면 피보호자·세션·발급 시각을 돌려준다")
    void 인가_정상() {
        when(ticketService.consume(TICKET)).thenReturn(new UploadTicket(WARD_ID, SESSION_ID, 1760400000000L));

        UploadGrant grant = service.authorize(TICKET);

        assertThat(grant).isEqualTo(new UploadGrant(WARD_ID, SESSION_ID, 1760400000000L));
    }

    @Test
    @DisplayName("인가: 킬 스위치가 꺼졌으면 티켓을 소비하지 않고 503")
    void 인가_킬스위치() {
        properties.setTicketEnabled(false);
        assertThat(errorOf(() -> service.authorize(TICKET))).isEqualTo(ErrorCode.CAMERA_UPLOAD_UNAVAILABLE);
        verifyNoInteractions(ticketService);
    }

    @Test
    @DisplayName("인가: 정지·탈퇴 진행 계정은 403, 없는 계정은 401")
    void 인가_계정_상태() {
        when(ticketService.consume(TICKET)).thenReturn(new UploadTicket(WARD_ID, SESSION_ID, 1L));
        when(userRepository.findStatusById(WARD_ID)).thenReturn(Optional.of(Status.RESTRICTED));
        assertThat(errorOf(() -> service.authorize(TICKET))).isEqualTo(ErrorCode.INACTIVE_USER);

        when(userRepository.findStatusById(WARD_ID)).thenReturn(Optional.empty());
        assertThat(errorOf(() -> service.authorize(TICKET))).isEqualTo(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
    }

    @Test
    @DisplayName("인가: 발급 뒤 카메라가 삭제·이전됐으면 404")
    void 인가_카메라_없음() {
        when(ticketService.consume(TICKET)).thenReturn(new UploadTicket(WARD_ID, SESSION_ID, 1L));
        when(cameraService.isOwnedSession(WARD_ID, SESSION_ID)).thenReturn(false);
        assertThat(errorOf(() -> service.authorize(TICKET))).isEqualTo(ErrorCode.CAMERA_NOT_FOUND);
    }

    @Test
    @DisplayName("인가: 발급 뒤 토큰이 무효화됐으면(비밀번호 변경·역할 변경 등) 거부")
    void 인가_무효화() {
        long issuedAt = System.currentTimeMillis() - 10_000;
        when(ticketService.consume(TICKET)).thenReturn(new UploadTicket(WARD_ID, SESSION_ID, issuedAt));
        when(valueOps.get(RedisKeys.PASSWORD_INVALIDATE + WARD_ID))
                .thenReturn(String.valueOf(System.currentTimeMillis() / 1000));

        assertThat(errorOf(() -> service.authorize(TICKET))).isEqualTo(ErrorCode.CAMERA_UPLOAD_TICKET_INVALID);
    }

    @Test
    @DisplayName("인가: 무효화 키 조회만 실패하면 통과(Redis 순단으로 새 송출이 막히지 않는다 - 티켓 소비는 이미 성공했다)")
    void 인가_무효화_조회_실패() {
        when(ticketService.consume(TICKET)).thenReturn(new UploadTicket(WARD_ID, SESSION_ID, 1L));
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(service.authorize(TICKET).sessionId()).isEqualTo(SESSION_ID);
    }

    // ---- 주기 재확인 ----

    @Test
    @DisplayName("재확인: 계정·카메라·플래그가 유지되면 계속")
    void 재확인_정상() {
        assertThat(service.isStillValid(new UploadGrant(WARD_ID, SESSION_ID, System.currentTimeMillis()))).isTrue();
    }

    @Test
    @DisplayName("재확인: 정지·탈퇴·카메라 삭제·플래그 제거면 끊는다")
    void 재확인_끊김() {
        UploadGrant grant = new UploadGrant(WARD_ID, SESSION_ID, System.currentTimeMillis());

        when(userRepository.findStatusById(WARD_ID)).thenReturn(Optional.of(Status.RESTRICTED));
        assertThat(service.isStillValid(grant)).isFalse();

        when(userRepository.findStatusById(WARD_ID)).thenReturn(Optional.of(Status.ACTIVE));
        when(cameraService.isOwnedSession(WARD_ID, SESSION_ID)).thenReturn(false);
        assertThat(service.isStillValid(grant)).isFalse();

        when(cameraService.isOwnedSession(WARD_ID, SESSION_ID)).thenReturn(true);
        properties.setWsH264SessionIds("");   // 롤백(env 한 줄) - 열린 연결도 재확인 주기 안에 JPEG로 돌아간다
        assertThat(service.isStillValid(grant)).isFalse();
    }

    @Test
    @DisplayName("재확인: DB 조회가 실패하면 끊는다(확인할 수 없으면 이어 주지 않는다)")
    void 재확인_DB_실패() {
        when(userRepository.findStatusById(anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(service.isStillValid(new UploadGrant(WARD_ID, SESSION_ID, 1L))).isFalse();
    }

    @Test
    @DisplayName("재확인: 무효화 키(Redis) 조회만 실패하면 이어 간다")
    void 재확인_Redis_실패는_이어감() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(service.isStillValid(new UploadGrant(WARD_ID, SESSION_ID, 1L))).isTrue();
    }

    @Test
    @DisplayName("재확인: 킬 스위치(ticket-enabled)는 열린 연결을 끊지 않는다 - 새 수립만 막는다")
    void 재확인_킬스위치_무관() {
        properties.setTicketEnabled(false);
        assertThat(service.isStillValid(new UploadGrant(WARD_ID, SESSION_ID, 1L))).isTrue();
        verify(rateLimitService, never()).check(anyString(), anyString(), anyInt(), anyInt());
        verify(userRepository).findStatusById(eq(WARD_ID));
    }

    private static String eq(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
