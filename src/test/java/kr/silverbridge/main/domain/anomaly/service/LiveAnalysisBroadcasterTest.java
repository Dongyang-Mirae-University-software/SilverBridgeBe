package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.dto.LiveAnalysisMessage;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.dto.LiveAnalysisSnapshot;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import kr.silverbridge.main.global.websocket.WebSocketRecipientStatusPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실시간 분석 상태 팬아웃 - 상태가 바뀔 때만, ACTIVE 보호자에게만, WS 게이트(정지 계정 차단)를 거쳐 보낸다.
 *
 * <p>발송은 실제 {@link WebSocketEventPublisher}를 쓰고 그 아래 메시징 템플릿·수신자 상태만 목으로 둔다 -
 * 정지 계정 차단이 이 새 이벤트에도 실제로 걸리는지까지 확인하기 위해서다.</p>
 */
@ExtendWith(MockitoExtension.class)
class LiveAnalysisBroadcasterTest {

    private static final String SESSION_ID = "ward_a9cC5f_live";
    private static final String WARD_ID = "a9cC5f";

    @Mock private CameraService cameraService;
    @Mock private ConnectionService connectionService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private WebSocketRecipientStatusPort recipientStatusPort;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private LiveAnalysisBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        WebSocketEventPublisher publisher = new WebSocketEventPublisher(messagingTemplate, recipientStatusPort);
        // 동기 실행기 - 발송 결과를 바로 검증한다
        broadcaster = new LiveAnalysisBroadcaster(cameraService, connectionService, publisher, Runnable::run, now::get);
        lenient().when(cameraService.findActiveOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner(WARD_ID, "거실")));
        lenient().when(recipientStatusPort.findStatus(anyString())).thenReturn(Optional.of(Status.ACTIVE));
    }

    private static AnomalySignal signal(DetectedType type, boolean danger, double confidence) {
        return new AnomalySignal(SESSION_ID, type, confidence, danger,
                OffsetDateTime.of(2026, 10, 3, 5, 3, 9, 0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("ACTIVE 보호자 전원에게 /topic/{보호자}/camera-analysis로 보낸다(피보호자 본인·다른 사람은 받지 않는다)")
    void ACTIVE보호자에게_발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1", "G2"));

        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.1));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/G1/camera-analysis"), payload.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/G2/camera-analysis"), any(Object.class));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/" + WARD_ID + "/camera-analysis"), any(Object.class));

        LiveAnalysisMessage message = (LiveAnalysisMessage) payload.getValue();
        assertThat(message.sessionId()).isEqualTo(SESSION_ID);
        assertThat(message.wardId()).isEqualTo(WARD_ID);
        assertThat(message.detectedType()).isEqualTo("NORMAL");
        assertThat(message.detectedTypeLabel()).isEqualTo("정상");
        assertThat(message.analyzedAt()).isEqualTo("2026-10-03T14:03:09+09:00");   // KST
    }

    @Test
    @DisplayName("정지(RESTRICTED) 보호자에게는 보내지 않는다 - 기존 WS 수신자 차단을 그대로 따른다")
    void 정지보호자_미발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1", "G2"));
        when(recipientStatusPort.findStatus("G2")).thenReturn(Optional.of(Status.RESTRICTED));

        broadcaster.onAnalysis(signal(DetectedType.FIRE, true, 0.8));

        verify(messagingTemplate).convertAndSend(eq("/topic/G1/camera-analysis"), any(Object.class));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/G2/camera-analysis"), any(Object.class));
    }

    @Test
    @DisplayName("같은 상태가 반복되면(프레임마다 오는 결과) 다시 보내지 않는다 - 신뢰도만 바뀌어도 변화가 아니다")
    void 같은상태_재발송안함() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1"));

        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.1));
        now.addAndGet(5_000);
        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.3));
        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.2));

        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/G1/camera-analysis"), any(Object.class));
    }

    @Test
    @DisplayName("상태가 바뀌어도 2초 안이면 미루고, 2초 뒤 다음 결과에서 최신 상태를 보낸다")
    void 간격제한() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1"));

        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.1));   // 발송 1
        now.addAndGet(500);
        broadcaster.onAnalysis(signal(DetectedType.FIRE, true, 0.9));      // 2초 안 - 미룸
        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));

        now.addAndGet(2_000);
        broadcaster.onAnalysis(signal(DetectedType.FIRE, true, 0.9));      // 발송 2

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/G1/camera-analysis"), payload.capture());
        LiveAnalysisMessage last = (LiveAnalysisMessage) payload.getAllValues().get(1);
        assertThat(last.detectedType()).isEqualTo("FIRE");
        assertThat(last.detectedTypeLabel()).isEqualTo("화재");
        assertThat(last.danger()).isTrue();
    }

    @Test
    @DisplayName("송출 상태 변화(running → disconnected)도 보낸다. AI 상태 이름은 계약 값으로 좁힌다")
    void 송출상태변화() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1"));

        broadcaster.onSessionStatus(SESSION_ID, "running");
        now.addAndGet(3_000);
        broadcaster.onSessionStatus(SESSION_ID, "running");
        broadcaster.onSessionStatus(SESSION_ID, "stopped");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/G1/camera-analysis"), payload.capture());
        assertThat(((LiveAnalysisMessage) payload.getAllValues().get(0)).status()).isEqualTo("running");
        assertThat(((LiveAnalysisMessage) payload.getAllValues().get(1)).status()).isEqualTo("offline");
    }

    @Test
    @DisplayName("세션이 끝나면 간격과 무관하게 바로 offline을 알리고 상태를 지운다")
    void 세션종료() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1"));
        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.1));

        broadcaster.onSessionsEnded(Set.of(SESSION_ID));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/G1/camera-analysis"), payload.capture());
        LiveAnalysisMessage ended = (LiveAnalysisMessage) payload.getAllValues().get(1);
        assertThat(ended.status()).isEqualTo("offline");
        assertThat(ended.detectedType()).isNull();
        assertThat(broadcaster.findLatest(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("삭제·비활성(꺼진) 카메라는 아무에게도 보내지 않는다 - 영상·목록과 같은 기준 (점검 L-1)")
    void 주인없음_미발송() {
        when(cameraService.findActiveOwnerBySessionId(SESSION_ID)).thenReturn(Optional.empty());

        broadcaster.onAnalysis(signal(DetectedType.FIRE, true, 0.9));

        verify(connectionService, never()).getActiveGuardianIds(anyString());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("최근 분석 스냅샷 - 받은 결과를 화면용 값으로 돌려주고, AI 연결이 끊기면(clear) 비운다")
    void 스냅샷() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of());
        broadcaster.onAnalysis(signal(DetectedType.UNKNOWN, false, 0.0));

        LiveAnalysisSnapshot snapshot = broadcaster.findLatest(SESSION_ID).orElseThrow();
        assertThat(snapshot.detectedType()).isEqualTo("UNKNOWN");
        assertThat(snapshot.detectedTypeLabel()).isEqualTo("확인 불가");
        assertThat(snapshot.analyzedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));

        broadcaster.clear();
        assertThat(broadcaster.findLatest(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("발송 중 오류는 삼킨다 - AI 수신 경로로 예외가 올라가지 않는다")
    void 발송오류_격리() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenThrow(new IllegalStateException("db"));

        broadcaster.onAnalysis(signal(DetectedType.FIRE, true, 0.9));   // 예외 없이 끝나야 한다

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("AI 연결이 끊기면 상태를 보낸 적 있는 세션에 status=null(확인 불가)을 보낸다 - 마지막 '정상'이 화면에 남지 않게 (점검 M-1)")
    void 연결끊김_확인불가_발송() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1"));
        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.1));

        broadcaster.clear();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/G1/camera-analysis"), payload.capture());
        LiveAnalysisMessage unknown = (LiveAnalysisMessage) payload.getAllValues().get(1);
        assertThat(unknown.status()).isNull();
        assertThat(unknown.detectedType()).isNull();
        assertThat(broadcaster.findLatest(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("아직 아무것도 보내지 않은 세션은 연결이 끊겨도 보내지 않는다")
    void 연결끊김_미발송세션_무시() {
        now.set(0);   // 첫 결과도 간격 제한에 걸려 보내지 않은 상태
        broadcaster.onSessionStatus(SESSION_ID, "running");

        broadcaster.clear();

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("송출 상태를 받기 전에 분석이 먼저 오면 running으로 채운다 - status=null은 '확인 불가'만 뜻한다")
    void 분석먼저_running() {
        when(connectionService.getActiveGuardianIds(WARD_ID)).thenReturn(List.of("G1"));

        broadcaster.onAnalysis(signal(DetectedType.NORMAL, false, 0.1));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/G1/camera-analysis"), payload.capture());
        assertThat(((LiveAnalysisMessage) payload.getValue()).status()).isEqualTo("running");
    }
}
