package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.service.AnomalyDetectionService;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.service.CameraService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiLiveStreamSubscriber 구독 상태 관리 테스트 (2026-07-14 점검 H-1 회귀 방지).
 *
 * 핵심: 세션이 AI 목록에서 사라지면 구독 기록도 지워야 한다. 안 지우면 카메라의 영속 session_id로
 * iPad가 재접속했을 때 "이미 구독함"으로 오판해 subscribe를 보내지 않고, 이상감지가 <b>에러 없이</b> 0건이 된다.
 */
@ExtendWith(MockitoExtension.class)
class AiLiveStreamSubscriberTest {

    private static final String SESSION_ID = "ward_a9cC5f_k3m";

    @Mock private AnomalySignalParser signalParser;
    @Mock private AnomalyDetectionService detectionService;
    @Mock private CameraService cameraService;
    @Mock private TaskScheduler taskScheduler;
    @Mock private WebSocketSession session;

    private AiLiveStreamSubscriber subscriber;

    @BeforeEach
    void setUp() throws Exception {
        subscriber = new AiLiveStreamSubscriber(new AnomalyProperties(), signalParser, detectionService,
                cameraService, new ObjectMapper(), taskScheduler);
        lenient().when(session.isOpen()).thenReturn(true);
        subscriber.afterConnectionEstablished(session);   // 연결 → {"action":"list"} 1건 발송
    }

    private TextMessage liveStreams(String... sessionIds) {
        String data = String.join(",",
                java.util.Arrays.stream(sessionIds).map(id -> "{\"sessionId\":\"" + id + "\"}").toList());
        return new TextMessage("{\"type\":\"live_streams\",\"data\":[" + data + "]}");
    }

    private boolean isSubscribeFor(TextMessage message, String sessionId) {
        return message.getPayload().contains("\"subscribe\"") && message.getPayload().contains(sessionId);
    }

    @Test
    @DisplayName("등록된 카메라 세션만 구독한다(미등록 세션은 무시)")
    void 등록세션만_구독() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));
        when(cameraService.findOwnerBySessionId("stream_001")).thenReturn(Optional.empty());

        subscriber.handleTextMessage(session, liveStreams(SESSION_ID, "stream_001"));

        verify(session).sendMessage(argThat(m -> isSubscribeFor((TextMessage) m, SESSION_ID)));
        verify(session, never()).sendMessage(argThat(m -> isSubscribeFor((TextMessage) m, "stream_001")));
    }

    @Test
    @DisplayName("이미 구독한 세션은 다시 subscribe하지 않는다(같은 목록이 반복돼도 1회)")
    void 중복구독_방지() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));

        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));
        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));

        verify(session, times(1)).sendMessage(argThat(m -> isSubscribeFor((TextMessage) m, SESSION_ID)));
    }

    @Test
    @DisplayName("세션이 목록에서 사라졌다가 같은 sessionId로 돌아오면 다시 구독한다 (H-1 — 조용한 침묵 방지)")
    void 세션_재시작시_재구독() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));

        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));   // 구독
        subscriber.handleTextMessage(session, liveStreams());             // 세션 종료 — 목록에서 사라짐
        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));   // 같은 sessionId로 재시작

        verify(session, times(2)).sendMessage(argThat(m -> isSubscribeFor((TextMessage) m, SESSION_ID)));
    }

    @Test
    @DisplayName("카메라가 등록되면 AI 세션 목록을 다시 요청한다(스트리밍이 먼저 시작된 경우 대비)")
    void 카메라등록시_목록_재요청() throws Exception {
        subscriber.onCameraRegistered(
                new kr.silverbridge.main.domain.camera.event.CameraRegisteredEvent("WD0001", SESSION_ID));

        // 연결 시 1회 + 등록 시 1회
        verify(session, times(2)).sendMessage(argThat(m -> ((TextMessage) m).getPayload().contains("\"list\"")));
    }

    @Test
    @DisplayName("latest_analysis는 판정 서비스로 넘긴다")
    void 분석신호_전달() throws Exception {
        when(signalParser.parse(any())).thenReturn(Optional.empty());

        subscriber.handleTextMessage(session, new TextMessage("{\"type\":\"latest_analysis\",\"data\":{}}"));

        verify(signalParser).parse(any());
    }
}
