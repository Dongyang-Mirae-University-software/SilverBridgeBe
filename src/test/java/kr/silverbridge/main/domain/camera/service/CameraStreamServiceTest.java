package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.client.AiStreamClient;
import kr.silverbridge.main.domain.camera.client.AiStreamClient.AiLiveStream;
import kr.silverbridge.main.domain.camera.client.AiStreamClient.AiMjpegStream;
import kr.silverbridge.main.domain.camera.client.AiStreamClient.AiStreamStatus;
import kr.silverbridge.main.domain.camera.client.AiStreamUnavailableException;
import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.domain.camera.dto.CameraLiveStatusResponse;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.dto.CameraResponse;
import kr.silverbridge.main.domain.camera.dto.GuardianCameraView;
import kr.silverbridge.main.domain.camera.dto.GuardianLiveCameraView;
import kr.silverbridge.main.domain.camera.dto.LiveAnalysisSnapshot;
import kr.silverbridge.main.domain.camera.dto.WardLiveCameraView;
import kr.silverbridge.main.domain.camera.service.CameraStreamService.StopReason;
import kr.silverbridge.main.domain.camera.service.CameraStreamTicketService.StreamTicket;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.exception.TooManyRequestsException;
import kr.silverbridge.main.global.security.RateLimitService;
import kr.silverbridge.main.global.util.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보호자 영상 중계 - 인가가 모든 경로를 막는지, 중계가 끝나면 자리·AI 연결이 반드시 정리되는지.
 */
@ExtendWith(MockitoExtension.class)
class CameraStreamServiceTest {

    private static final String GUARDIAN_ID = "GRD001";
    private static final String WARD_ID = "a9cC5f";
    private static final String SESSION_ID = "ward_a9cC5f_live";
    private static final String TICKET = "st_" + "A".repeat(43);

    @Mock private CameraService cameraService;
    @Mock private AiStreamClient aiStreamClient;
    @Mock private CameraStreamTicketService ticketService;
    @Mock private LiveAnalysisSnapshotPort analysisSnapshots;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private RateLimitService rateLimitService;

    private CameraStreamProperties properties;
    private CameraStreamSlots slots;
    private CameraStreamService service;

    @BeforeEach
    void setUp() {
        properties = new CameraStreamProperties();
        slots = new CameraStreamSlots(properties);
        service = new CameraStreamService(cameraService, aiStreamClient, ticketService, slots, analysisSnapshots,
                userRepository, redisTemplate, properties, rateLimitService);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private static ErrorCode errorOf(ThrowingRunnable call) {
        try {
            call.run();
        } catch (CustomException e) {
            return e.getErrorCode();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("예외가 나지 않았다");
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static GuardianCameraView camera(String sessionId, String label) {
        return new GuardianCameraView(sessionId, WARD_ID, "남궁명진", label, true);
    }

    @Nested
    @DisplayName("피보호자 내 카메라 - 연결 상태 (2026-10-05)")
    class WardLive {

        private CameraResponse mine(Long id, String sessionId, String label) {
            return new CameraResponse(id, sessionId, "dev_" + id, label, true, 5, OffsetDateTime.parse("2026-10-05T10:00:00+09:00"));
        }

        @Test
        @DisplayName("본인 카메라에 송출 상태를 붙인다 - running·disconnected·offline(AI 목록에 없음), AI는 1번만 부른다")
        void 상태_구분() {
            OffsetDateTime last = OffsetDateTime.parse("2026-10-05T10:01:00+09:00");
            when(cameraService.getMyCameras(WARD_ID)).thenReturn(List.of(
                    mine(1L, "s1", "거실"), mine(2L, "s2", "주방"), mine(3L, "s3", "침실")));
            when(aiStreamClient.fetchLiveStreams()).thenReturn(Map.of(
                    "s1", new AiLiveStream("s1", "running", last),
                    "s2", new AiLiveStream("s2", "disconnected", null)));

            List<WardLiveCameraView> views = service.getWardLiveCameras(WARD_ID);

            assertThat(views).extracting(WardLiveCameraView::label, WardLiveCameraView::status)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("거실", "running"),
                            org.assertj.core.groups.Tuple.tuple("주방", "disconnected"),
                            org.assertj.core.groups.Tuple.tuple("침실", "offline"));
            assertThat(views.get(0).lastFrameAt()).isEqualTo(last);
            assertThat(views.get(0).id()).isEqualTo(1L);
            assertThat(views.get(0).deviceId()).isEqualTo("dev_1");
            verify(aiStreamClient).fetchLiveStreams();
            verify(rateLimitService).check("camera-ward-live", WARD_ID, 30, 600);
        }

        @Test
        @DisplayName("AI 장애면 목록은 그대로, 상태만 null(확인 중) - 연결 안 됨으로 채우지 않는다")
        void AI장애_상태null() {
            when(cameraService.getMyCameras(WARD_ID)).thenReturn(List.of(mine(1L, "s1", "거실")));
            when(aiStreamClient.fetchLiveStreams()).thenThrow(new AiStreamUnavailableException("down"));

            List<WardLiveCameraView> views = service.getWardLiveCameras(WARD_ID);

            assertThat(views).singleElement().satisfies(v -> {
                assertThat(v.label()).isEqualTo("거실");
                assertThat(v.status()).isNull();
                assertThat(v.lastFrameAt()).isNull();
            });
        }

        @Test
        @DisplayName("카메라가 없으면 AI를 부르지 않는다")
        void 카메라없음_AI미호출() {
            when(cameraService.getMyCameras(WARD_ID)).thenReturn(List.of());

            assertThat(service.getWardLiveCameras(WARD_ID)).isEmpty();
            verify(aiStreamClient, never()).fetchLiveStreams();
        }

        @Test
        @DisplayName("속도 제한에 걸리면 본인 카메라 조회·AI 호출 없이 429")
        void 속도제한() {
            org.mockito.Mockito.doThrow(new TooManyRequestsException(60))
                    .when(rateLimitService).check(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.anyInt());

            assertThat(errorOf(() -> service.getWardLiveCameras(WARD_ID))).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            verify(cameraService, never()).getMyCameras(any());
            verify(aiStreamClient, never()).fetchLiveStreams();
        }
    }

    @Nested
    @DisplayName("목록·상태")
    class ListAndStatus {

        @Test
        @DisplayName("송출 중·끊김·미송출을 구분하고, AI 목록에 없는 카메라는 offline")
        void 상태_구분() {
            when(cameraService.getConnectedWardCameras(GUARDIAN_ID)).thenReturn(List.of(
                    camera("s1", "거실"), camera("s2", "주방"), camera("s3", "안방")));
            when(aiStreamClient.fetchLiveStreams()).thenReturn(Map.of(
                    "s1", new AiLiveStream("s1", "running", null),
                    "s2", new AiLiveStream("s2", "disconnected", null)));

            List<GuardianLiveCameraView> views = service.getLiveCameras(GUARDIAN_ID);

            assertThat(views).extracting(GuardianLiveCameraView::label, GuardianLiveCameraView::status)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("거실", "running"),
                            org.assertj.core.groups.Tuple.tuple("주방", "disconnected"),
                            org.assertj.core.groups.Tuple.tuple("안방", "offline"));
        }

        @Test
        @DisplayName("AI 장애면 카메라 목록은 그대로 주고 상태만 null(확인 불가) - offline으로 채우지 않는다")
        void AI장애_상태null() {
            when(cameraService.getConnectedWardCameras(GUARDIAN_ID)).thenReturn(List.of(camera("s1", "거실")));
            when(aiStreamClient.fetchLiveStreams()).thenThrow(new AiStreamUnavailableException("down"));

            List<GuardianLiveCameraView> views = service.getLiveCameras(GUARDIAN_ID);

            assertThat(views).hasSize(1);
            assertThat(views.get(0).status()).isNull();
            assertThat(views.get(0).label()).isEqualTo("거실");
        }

        @Test
        @DisplayName("볼 수 있는 카메라가 없으면 AI를 부르지 않는다")
        void 카메라없음_AI미호출() {
            when(cameraService.getConnectedWardCameras(GUARDIAN_ID)).thenReturn(List.of());

            assertThat(service.getLiveCameras(GUARDIAN_ID)).isEmpty();
            verify(aiStreamClient, never()).fetchLiveStreams();
        }

        @Test
        @DisplayName("상태: AI가 세션을 모르면 offline, 받아 둔 분석은 함께 싣는다")
        void 상태조회() {
            when(aiStreamClient.fetchStatus(SESSION_ID)).thenReturn(Optional.empty());
            assertThat(service.getStatus(GUARDIAN_ID, SESSION_ID).status()).isEqualTo("offline");

            LiveAnalysisSnapshot snapshot = new LiveAnalysisSnapshot("NORMAL", "정상", 0.1, false, null);
            when(aiStreamClient.fetchStatus(SESSION_ID))
                    .thenReturn(Optional.of(new AiStreamStatus("running", null, 1.9, true)));
            when(analysisSnapshots.findLatest(SESSION_ID)).thenReturn(Optional.of(snapshot));

            CameraLiveStatusResponse response = service.getStatus(GUARDIAN_ID, SESSION_ID);
            assertThat(response.status()).isEqualTo("running");
            assertThat(response.analysis()).isEqualTo(snapshot);
        }

        @Test
        @DisplayName("상태: AI 장애는 503")
        void 상태_AI장애_503() {
            when(aiStreamClient.fetchStatus(SESSION_ID)).thenThrow(new AiStreamUnavailableException("down"));

            assertThat(errorOf(() -> service.getStatus(GUARDIAN_ID, SESSION_ID)))
                    .isEqualTo(ErrorCode.CAMERA_STREAM_UNAVAILABLE);
        }

        @Test
        @DisplayName("연결 안 된 카메라면 상태·스냅샷·티켓 모두 AI를 부르기 전에 막힌다")
        void 인가실패_AI미호출() {
            when(cameraService.getViewableCamera(GUARDIAN_ID, SESSION_ID))
                    .thenThrow(new CustomException(ErrorCode.CAMERA_NOT_CONNECTED));

            assertThat(errorOf(() -> service.getStatus(GUARDIAN_ID, SESSION_ID))).isEqualTo(ErrorCode.CAMERA_NOT_CONNECTED);
            assertThat(errorOf(() -> service.getLatestFrame(GUARDIAN_ID, SESSION_ID))).isEqualTo(ErrorCode.CAMERA_NOT_CONNECTED);
            assertThat(errorOf(() -> service.issueTicket(GUARDIAN_ID, SESSION_ID))).isEqualTo(ErrorCode.CAMERA_NOT_CONNECTED);
            verify(aiStreamClient, never()).fetchStatus(anyString());
            verify(aiStreamClient, never()).fetchLatestFrame(anyString());
            verify(ticketService, never()).issue(anyString(), anyString());
        }

        @Test
        @DisplayName("스냅샷: 아직 프레임이 없으면 404 CAMERA_NOT_STREAMING")
        void 스냅샷_없음_404() {
            when(aiStreamClient.fetchLatestFrame(SESSION_ID)).thenReturn(Optional.empty());

            assertThat(errorOf(() -> service.getLatestFrame(GUARDIAN_ID, SESSION_ID)))
                    .isEqualTo(ErrorCode.CAMERA_NOT_STREAMING);
        }
    }

    @Nested
    @DisplayName("영상 중계 - 시작 전 차단")
    class RelayGate {

        @BeforeEach
        void ticket() {
            when(ticketService.consume(TICKET, SESSION_ID)).thenReturn(new StreamTicket(GUARDIAN_ID, System.currentTimeMillis()));
        }

        @Test
        @DisplayName("이용 제한(RESTRICTED) 계정 → 403 INACTIVE_USER, AI 연결·자리 없음")
        void 정지계정_차단() {
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.RESTRICTED));

            assertThat(errorOf(() -> service.relay(SESSION_ID, TICKET, new MockHttpServletResponse())))
                    .isEqualTo(ErrorCode.INACTIVE_USER);
            verify(aiStreamClient, never()).openMjpeg(anyString());
            assertThat(slots.activeCount()).isZero();
        }

        @Test
        @DisplayName("연결이 끊긴·PENDING·피보호자 계정 등 연결 인가 실패 → 403, AI 연결 없음")
        void 연결없음_차단() {
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.ACTIVE));
            when(cameraService.getViewableCamera(GUARDIAN_ID, SESSION_ID))
                    .thenThrow(new CustomException(ErrorCode.CAMERA_NOT_CONNECTED));

            assertThat(errorOf(() -> service.relay(SESSION_ID, TICKET, new MockHttpServletResponse())))
                    .isEqualTo(ErrorCode.CAMERA_NOT_CONNECTED);
            verify(aiStreamClient, never()).openMjpeg(anyString());
            assertThat(slots.activeCount()).isZero();
        }

        @Test
        @DisplayName("티켓 발급 뒤 토큰이 무효화됐으면(비밀번호 변경·정지·역할 변경) → 401")
        void 무효화_차단() {
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.ACTIVE));
            when(valueOps.get(RedisKeys.PASSWORD_INVALIDATE + GUARDIAN_ID))
                    .thenReturn(String.valueOf(System.currentTimeMillis() / 1000 + 60));

            assertThat(errorOf(() -> service.relay(SESSION_ID, TICKET, new MockHttpServletResponse())))
                    .isEqualTo(ErrorCode.CAMERA_STREAM_TICKET_INVALID);
            verify(aiStreamClient, never()).openMjpeg(anyString());
        }

        @Test
        @DisplayName("AI 장애 → 503이고 자리를 돌려준다")
        void AI장애_자리반납() {
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.ACTIVE));
            when(cameraService.getViewableCamera(GUARDIAN_ID, SESSION_ID)).thenReturn(new CameraOwner(WARD_ID, "거실"));
            when(aiStreamClient.openMjpeg(SESSION_ID)).thenThrow(new AiStreamUnavailableException("down"));

            assertThat(errorOf(() -> service.relay(SESSION_ID, TICKET, new MockHttpServletResponse())))
                    .isEqualTo(ErrorCode.CAMERA_STREAM_UNAVAILABLE);
            assertThat(slots.activeCount()).isZero();
        }
    }

    @Nested
    @DisplayName("영상 중계 - 복사와 정리")
    class RelayCopy {

        private final java.util.concurrent.atomic.AtomicInteger disconnects = new java.util.concurrent.atomic.AtomicInteger();

        @BeforeEach
        void allowed() {
            when(ticketService.consume(TICKET, SESSION_ID)).thenReturn(new StreamTicket(GUARDIAN_ID, System.currentTimeMillis()));
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.ACTIVE));
            when(cameraService.getViewableCamera(GUARDIAN_ID, SESSION_ID)).thenReturn(new CameraOwner(WARD_ID, "거실"));
        }

        private void aiSends(InputStream body) {
            when(aiStreamClient.openMjpeg(SESSION_ID)).thenReturn(new AiMjpegStream(
                    "multipart/x-mixed-replace; boundary=frame", body, disconnects::incrementAndGet));
        }

        @Test
        @DisplayName("AI 바이트를 그대로 복사하고, 끝나면 AI 연결을 닫고 자리를 돌려준다")
        void 복사_정리() throws Exception {
            byte[] frames = "--frame\r\nContent-Type: image/jpeg\r\n\r\nJPEG\r\n".getBytes();
            aiSends(new ByteArrayInputStream(frames));
            MockHttpServletResponse response = new MockHttpServletResponse();

            service.relay(SESSION_ID, TICKET, response);

            assertThat(response.getContentAsByteArray()).isEqualTo(frames);
            assertThat(response.getContentType()).startsWith("multipart/x-mixed-replace");
            assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(disconnects).hasValue(1);
            assertThat(slots.activeCount()).isZero();
        }

        @Test
        @DisplayName("시청자가 닫으면(쓰기 실패) 조용히 끝내고 AI 연결·자리를 정리한다")
        void 시청자이탈_정리() throws Exception {
            aiSends(endless());
            MockHttpServletResponse response = new MockHttpServletResponse() {
                @Override
                public jakarta.servlet.ServletOutputStream getOutputStream() {
                    return new jakarta.servlet.ServletOutputStream() {
                        @Override public boolean isReady() { return true; }
                        @Override public void setWriteListener(jakarta.servlet.WriteListener l) { }
                        @Override public void write(int b) throws IOException { throw new IOException("Broken pipe"); }
                        @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("Broken pipe"); }
                    };
                }
            };

            service.relay(SESSION_ID, TICKET, response);

            assertThat(disconnects).hasValue(1);
            assertThat(slots.activeCount()).isZero();
        }
    }

    @Nested
    @DisplayName("영상 중계 - 시청 중 종료 조건 (pump)")
    class Pump {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Test
        @DisplayName("최대 시청 시간이 지나면 끊는다(AI MJPEG는 스스로 끝나지 않는다)")
        void 최대시간() {
            properties.setMaxStreamDuration(Duration.ZERO);

            assertThat(service.pump(endless(), out, GUARDIAN_ID, SESSION_ID, 0)).isEqualTo(StopReason.MAX_DURATION);
        }

        @Test
        @DisplayName("보는 중 연결이 해제되면 재확인 때 끊는다")
        void 연결해제_재확인() {
            properties.setRevalidateInterval(Duration.ZERO);
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.ACTIVE));
            when(cameraService.isViewable(GUARDIAN_ID, SESSION_ID)).thenReturn(false);

            assertThat(service.pump(endless(), out, GUARDIAN_ID, SESSION_ID, 0)).isEqualTo(StopReason.REVOKED);
        }

        @Test
        @DisplayName("보는 중 계정이 정지되면 재확인 때 끊는다")
        void 정지_재확인() {
            properties.setRevalidateInterval(Duration.ZERO);
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.RESTRICTED));

            assertThat(service.pump(endless(), out, GUARDIAN_ID, SESSION_ID, 0)).isEqualTo(StopReason.REVOKED);
        }

        @Test
        @DisplayName("AI가 무수신 제한 동안 아무것도 안 보내면 끊는다")
        void 무수신() {
            InputStream stalled = new InputStream() {
                @Override public int read() throws IOException { throw new SocketTimeoutException("idle"); }
                @Override public int read(byte[] b, int off, int len) throws IOException { throw new SocketTimeoutException("idle"); }
            };

            assertThat(service.pump(stalled, out, GUARDIAN_ID, SESSION_ID, 0)).isEqualTo(StopReason.AI_IDLE);
        }

        @Test
        @DisplayName("서버 종료가 시작되면 다음 바이트에서 끊는다")
        void 서버종료() {
            slots.stop();

            assertThat(service.pump(endless(), out, GUARDIAN_ID, SESSION_ID, 0)).isEqualTo(StopReason.SHUTDOWN);
        }
    }

    @Nested
    @DisplayName("시청 중 재확인 (isStillViewable)")
    class StillViewable {

        @Test
        @DisplayName("계정 행이 사라졌으면(탈퇴) 끊는다")
        void 탈퇴() {
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.empty());

            assertThat(service.isStillViewable(GUARDIAN_ID, SESSION_ID, 0)).isFalse();
        }

        @Test
        @DisplayName("DB 조회가 실패하면 끊는다(확인할 수 없으면 계속 보여주지 않는다)")
        void DB장애() {
            when(userRepository.findStatusById(GUARDIAN_ID)).thenThrow(new IllegalStateException("db"));

            assertThat(service.isStillViewable(GUARDIAN_ID, SESSION_ID, 0)).isFalse();
        }

        @Test
        @DisplayName("무효화 키(Redis) 조회만 실패하면 계정·연결 확인으로 이어 간다")
        void Redis장애_계속() {
            when(userRepository.findStatusById(GUARDIAN_ID)).thenReturn(Optional.of(Status.ACTIVE));
            when(cameraService.isViewable(GUARDIAN_ID, SESSION_ID)).thenReturn(true);
            when(valueOps.get(any())).thenThrow(new RedisConnectionFailureException("down"));

            assertThat(service.isStillViewable(GUARDIAN_ID, SESSION_ID, 0)).isTrue();
        }
    }

    @Nested
    @DisplayName("속도 제한 (점검 L-4)")
    class RateLimit {

        @Test
        @DisplayName("API마다 보호자 ID 기준 분·시간 한도로 검사한다")
        void 한도_검사() {
            when(cameraService.getConnectedWardCameras(GUARDIAN_ID)).thenReturn(List.of());
            when(aiStreamClient.fetchStatus(SESSION_ID)).thenReturn(Optional.empty());
            when(aiStreamClient.fetchLatestFrame(SESSION_ID)).thenReturn(Optional.of(new byte[]{1}));
            when(ticketService.issue(GUARDIAN_ID, SESSION_ID)).thenReturn(TICKET);

            service.getLiveCameras(GUARDIAN_ID);
            service.getStatus(GUARDIAN_ID, SESSION_ID);
            service.getLatestFrame(GUARDIAN_ID, SESSION_ID);
            service.issueTicket(GUARDIAN_ID, SESSION_ID);

            verify(rateLimitService).check("camera-live", GUARDIAN_ID, 30, 600);
            verify(rateLimitService).check("camera-status", GUARDIAN_ID, 30, 600);
            verify(rateLimitService).check("camera-frame", GUARDIAN_ID, 60, 1200);
            verify(rateLimitService).check("camera-ticket", GUARDIAN_ID, 20, 300);
        }

        @Test
        @DisplayName("한도를 넘으면 429 - 인가·AI 호출·티켓 발급 전에 막힌다")
        void 한도초과_조기차단() {
            org.mockito.Mockito.doThrow(new TooManyRequestsException(30))
                    .when(rateLimitService).check(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.anyInt());

            assertThat(errorOf(() -> service.getLiveCameras(GUARDIAN_ID))).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            assertThat(errorOf(() -> service.getStatus(GUARDIAN_ID, SESSION_ID))).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            assertThat(errorOf(() -> service.getLatestFrame(GUARDIAN_ID, SESSION_ID))).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            assertThat(errorOf(() -> service.issueTicket(GUARDIAN_ID, SESSION_ID))).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            verify(cameraService, never()).getConnectedWardCameras(anyString());
            verify(cameraService, never()).getViewableCamera(anyString(), anyString());
            verify(aiStreamClient, never()).fetchStatus(anyString());
            verify(ticketService, never()).issue(anyString(), anyString());
        }
    }

    /** AI MJPEG처럼 끝나지 않는 스트림. */
    private static InputStream endless() {
        return new InputStream() {
            @Override public int read() { return 'x'; }
            @Override public int read(byte[] b, int off, int len) {
                b[off] = 'x';
                return 1;
            }
        };
    }
}
