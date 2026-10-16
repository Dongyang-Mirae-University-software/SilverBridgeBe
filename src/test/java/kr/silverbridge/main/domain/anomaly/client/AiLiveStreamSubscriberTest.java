package kr.silverbridge.main.domain.anomaly.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.service.AnomalyDetectionService;
import kr.silverbridge.main.domain.anomaly.service.LiveAnalysisBroadcaster;
import kr.silverbridge.main.global.enums.DetectedType;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.service.CameraService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private LiveAnalysisBroadcaster liveAnalysisBroadcaster;
    @Mock private kr.silverbridge.main.domain.anomaly.service.CameraLinkLostNotifier linkLostNotifier;

    private AiLiveStreamSubscriber subscriber;

    @BeforeEach
    void setUp() throws Exception {
        subscriber = new AiLiveStreamSubscriber(new AnomalyProperties(), signalParser, detectionService,
                cameraService, new ObjectMapper(), taskScheduler, liveAnalysisBroadcaster, linkLostNotifier);
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
    @DisplayName("카메라 등록 이벤트는 커밋 후에 처리한다 - 커밋 전에 요청하면 AI 응답이 먼저 와 방금 등록한 카메라를 미등록으로 본다")
    void 카메라등록_이벤트는_커밋후에_처리한다() throws Exception {
        TransactionalEventListener listener = AiLiveStreamSubscriber.class
                .getMethod("onCameraRegistered", kr.silverbridge.main.domain.camera.event.CameraRegisteredEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("재동기화는 연결돼 있으면 세션 목록을 다시 요청한다 - AI broadcast가 빠져도 구독이 맞춰진다 (QA BE-1)")
    void 재동기화_목록_재요청() throws Exception {
        subscriber.resync();

        // 연결 시 1회 + 재동기화 1회
        verify(session, times(2)).sendMessage(argThat(m -> ((TextMessage) m).getPayload().contains("\"list\"")));
    }

    @Test
    @DisplayName("재동기화는 연결이 없으면 요청하지 않는다")
    void 재동기화_연결없으면_건너뜀() throws Exception {
        when(session.isOpen()).thenReturn(false);

        subscriber.resync();

        verify(session, times(1)).sendMessage(any());   // 연결 시 1회뿐
    }

    @Test
    @DisplayName("재동기화로 받은 목록에 아직 구독 안 한 등록 카메라가 있으면 구독한다 (놓친 broadcast 보완)")
    void 재동기화_놓친세션_구독() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));

        subscriber.resync();
        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));

        verify(session).sendMessage(argThat(m -> isSubscribeFor((TextMessage) m, SESSION_ID)));
    }

    @Test
    @DisplayName("latest_analysis는 판정 서비스로 넘긴다")
    void 분석신호_전달() throws Exception {
        when(signalParser.parse(any())).thenReturn(Optional.empty());

        subscriber.handleTextMessage(session, new TextMessage("{\"type\":\"latest_analysis\",\"data\":{}}"));

        verify(signalParser).parse(any());
    }

    @Test
    @DisplayName("latest_analysis는 판정을 먼저 거친 뒤 실시간 분석 상태로도 넘긴다 (2026-10-03)")
    void 분석신호_판정후_실시간상태로_전달() throws Exception {
        AnomalySignal signal = new AnomalySignal(SESSION_ID, DetectedType.NORMAL, 0.1, false, null);
        when(signalParser.parse(any())).thenReturn(Optional.of(signal));
        when(signalParser.expand(any(), org.mockito.ArgumentMatchers.eq(signal))).thenReturn(java.util.List.of(signal));

        subscriber.handleTextMessage(session, new TextMessage("{\"type\":\"latest_analysis\"}"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(detectionService, liveAnalysisBroadcaster);
        order.verify(detectionService).handle(signal);
        order.verify(liveAnalysisBroadcaster).onAnalysis(signal);
    }

    @Test
    @DisplayName("session_status는 세션 ID와 상태만 실시간 분석 상태로 넘긴다")
    void 세션상태_전달() throws Exception {
        subscriber.handleTextMessage(session, new TextMessage(
                "{\"type\":\"session_status\",\"sessionId\":\"" + SESSION_ID
                        + "\",\"data\":{\"status\":\"running\",\"fps\":1.9}}"));

        verify(liveAnalysisBroadcaster).onSessionStatus(SESSION_ID, "running");
    }

    @Test
    @DisplayName("목록에서 사라진 구독 세션은 종료로 알린다(보호자 화면 꺼짐 표시)")
    void 종료세션_알림() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));

        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));
        subscriber.handleTextMessage(session, liveStreams());

        verify(liveAnalysisBroadcaster).onSessionsEnded(Set.of(SESSION_ID));
    }

    @Test
    @DisplayName("AI 연결이 끊기면 받아 둔 분석 상태를 비운다 - 옛 결과를 현재처럼 보여주지 않는다")
    void 연결종료시_상태_초기화() {
        subscriber.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.GOING_AWAY);

        // 연결 시 1회 + 종료 시 1회
        verify(liveAnalysisBroadcaster, times(2)).clear();
    }

    @Test
    @DisplayName("동시 위험이면 종류마다 판정하고, 화면 상태는 대표 1건만 보낸다 (2026-10-16)")
    void 동시위험_종류별_판정() throws Exception {
        AnomalySignal weapon = new AnomalySignal(SESSION_ID, DetectedType.WEAPON, 0.9, true, null);
        AnomalySignal fall = new AnomalySignal(SESSION_ID, DetectedType.FALL, 0.8, true, null);
        when(signalParser.parse(any())).thenReturn(Optional.of(weapon));
        when(signalParser.expand(any(), org.mockito.ArgumentMatchers.eq(weapon)))
                .thenReturn(java.util.List.of(weapon, fall));

        subscriber.handleTextMessage(session, new TextMessage("{\"type\":\"latest_analysis\"}"));

        verify(detectionService).handle(weapon);
        verify(detectionService).handle(fall);
        verify(liveAnalysisBroadcaster, times(1)).onAnalysis(any());
        verify(liveAnalysisBroadcaster).onAnalysis(weapon);
    }

    @Test
    @DisplayName("한 종류의 처리 실패가 다른 종류를 막지 않는다")
    void 종류_하나_실패해도_나머지_처리() throws Exception {
        AnomalySignal weapon = new AnomalySignal(SESSION_ID, DetectedType.WEAPON, 0.9, true, null);
        AnomalySignal fall = new AnomalySignal(SESSION_ID, DetectedType.FALL, 0.8, true, null);
        when(signalParser.parse(any())).thenReturn(Optional.of(weapon));
        when(signalParser.expand(any(), org.mockito.ArgumentMatchers.eq(weapon)))
                .thenReturn(java.util.List.of(weapon, fall));
        when(detectionService.handle(weapon)).thenThrow(new IllegalStateException("db"));

        subscriber.handleTextMessage(session, new TextMessage("{\"type\":\"latest_analysis\"}"));

        verify(detectionService).handle(fall);
    }

    @Test
    @DisplayName("재접속 뒤 첫 목록에서 빠진 등록 카메라는 꺼짐으로 알리고 끊김 알림 유예를 시작한다")
    void 재접속뒤_사라진_세션() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));
        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));   // 구독
        subscriber.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.GOING_AWAY);
        subscriber.afterConnectionEstablished(session);                   // 재접속(구독 기록 비움)

        subscriber.handleTextMessage(session, liveStreams());             // 첫 목록에 없다

        verify(liveAnalysisBroadcaster).onSessionsOffline(Set.of(SESSION_ID));
        verify(linkLostNotifier).onLost(Set.of(SESSION_ID));
    }

    @Test
    @DisplayName("재접속 뒤 목록에 돌아온 세션은 구독하고 끊김 알림을 취소한다")
    void 재접속뒤_돌아온_세션() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));
        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));
        subscriber.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.GOING_AWAY);
        subscriber.afterConnectionEstablished(session);

        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));

        verify(liveAnalysisBroadcaster, never()).onSessionsOffline(any());
        verify(linkLostNotifier, never()).onLost(any());
        verify(linkLostNotifier, times(2)).onRecovered(SESSION_ID);   // 최초 구독 + 재접속 뒤 복귀
        verify(linkLostNotifier).cancelAll();
    }

    @Test
    @DisplayName("수신이 오래 없으면 재동기화 때 연결을 닫아 재접속 경로로 보낸다")
    void 수신없으면_연결_닫음() throws Exception {
        AnomalyProperties props = new AnomalyProperties();
        props.setResyncSeconds(1);
        props.setStaleResyncMultiplier(1);
        AiLiveStreamSubscriber stale = new AiLiveStreamSubscriber(props, signalParser, detectionService,
                cameraService, new ObjectMapper(), taskScheduler, liveAnalysisBroadcaster, linkLostNotifier);
        stale.afterConnectionEstablished(session);
        Thread.sleep(1200);

        stale.resync();

        verify(session).close(org.springframework.web.socket.CloseStatus.GOING_AWAY);
    }

    @Test
    @DisplayName("수신이 있으면 닫지 않는다")
    void 수신있으면_닫지_않음() throws Exception {
        subscriber.resync();

        verify(session, never()).close(any(org.springframework.web.socket.CloseStatus.class));
    }
}
