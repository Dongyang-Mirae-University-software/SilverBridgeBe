package kr.silverbridge.main.domain.anomaly.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.DeploymentException;
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
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

    @Test
    @DisplayName("관리자 스냅샷: 연결 직후 첫 목록 전에는 '알 수 없음'(빈 집합과 다르다) (2026-10-23)")
    void 스냅샷_첫목록_전에는_알수없음() {
        assertThat(subscriber.liveSessionSnapshot()).isEmpty();
    }

    @Test
    @DisplayName("관리자 스냅샷: 첫 목록을 처리하면 구독 중인 세션 집합을 준다 - 송출 0대면 빈 집합")
    void 스냅샷_목록_뒤에는_구독집합() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));

        subscriber.handleTextMessage(session, liveStreams());
        assertThat(subscriber.liveSessionSnapshot()).contains(Set.of());

        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));
        assertThat(subscriber.liveSessionSnapshot()).contains(Set.of(SESSION_ID));
    }

    @Test
    @DisplayName("관리자 스냅샷: 연결이 끊기거나 재접속하면 다시 '알 수 없음'")
    void 스냅샷_끊기면_알수없음() throws Exception {
        subscriber.handleTextMessage(session, liveStreams());
        assertThat(subscriber.liveSessionSnapshot()).isPresent();

        subscriber.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.GOING_AWAY);
        assertThat(subscriber.liveSessionSnapshot()).isEmpty();

        subscriber.afterConnectionEstablished(session);
        assertThat(subscriber.liveSessionSnapshot()).isEmpty();
    }

    @Test
    @DisplayName("관리자 스냅샷: 소켓이 닫혀 있으면 목록을 받았어도 '알 수 없음'")
    void 스냅샷_소켓닫힘() throws Exception {
        subscriber.handleTextMessage(session, liveStreams());
        when(session.isOpen()).thenReturn(false);

        assertThat(subscriber.liveSessionSnapshot()).isEmpty();
    }

    @Test
    @DisplayName("종료(stop) 뒤에는 수신 메시지를 처리하지 않고 재접속도 예약하지 않는다 - 알림 풀을 비운 뒤 신호가 들어오지 않게(2026-11-22 I-2)")
    void 종료_뒤_수신_중단() throws Exception {
        subscriber.start();
        assertThat(subscriber.isRunning()).isTrue();

        subscriber.stop();
        subscriber.stop();   // 빈 파괴 때(@PreDestroy) 한 번 더 불려도 안전하다

        assertThat(subscriber.isRunning()).isFalse();
        verify(session, org.mockito.Mockito.atLeastOnce()).close(org.springframework.web.socket.CloseStatus.NORMAL);

        subscriber.handleTextMessage(session, liveStreams(SESSION_ID));
        subscriber.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        // 종료 확인보다 먼저 세고 finally에서 내린다 - 순서가 뒤집히면 가드로 빠진 프레임이 감소만 해 -1이 된다(U-2)
        assertThat(inFlightFrames()).isZero();
        verify(cameraService, never()).findOwnerBySessionId(any());
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    @DisplayName("종료가 시작된 뒤 뒤늦게 성립한 연결은 받지 않고 닫는다")
    void 종료_뒤_성립한_연결은_닫는다() throws Exception {
        subscriber.stop();
        WebSocketSession late = org.mockito.Mockito.mock(WebSocketSession.class);

        subscriber.afterConnectionEstablished(late);

        verify(late).close(org.springframework.web.socket.CloseStatus.NORMAL);
        verify(late, never()).sendMessage(any());
    }

    @Test
    @DisplayName("lifecycle start()는 접속하지 않는다 - 접속은 ApplicationReadyEvent 리스너(connectOnReady)만 한다(기동을 막지 않는다)")
    void lifecycle_start는_접속하지_않는다() throws Exception {
        AiLiveStreamSubscriber fresh = new AiLiveStreamSubscriber(new AnomalyProperties(), signalParser, detectionService,
                cameraService, new ObjectMapper(), taskScheduler, liveAnalysisBroadcaster, linkLostNotifier);

        fresh.start();

        assertThat(fresh.isRunning()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(taskScheduler);
        // start()에 리스너를 붙이면 SmartLifecycle이 refresh 중에 부르는 것과 겹쳐 두 번 접속한다.
        assertThat(AiLiveStreamSubscriber.class.getMethod("start")
                .isAnnotationPresent(org.springframework.context.event.EventListener.class)).isFalse();
        org.springframework.context.event.EventListener onReady = AiLiveStreamSubscriber.class
                .getMethod("connectOnReady").getAnnotation(org.springframework.context.event.EventListener.class);
        assertThat(onReady).isNotNull();
        assertThat(onReady.value())
                .containsExactly(org.springframework.boot.context.event.ApplicationReadyEvent.class);
    }

    // ── 종료 때 처리 중이던 프레임 대기 (2026-11-22 점검 S-1) ─────────────────────────────

    @Test
    @DisplayName("stop은 이미 처리에 들어간 프레임이 끝난 뒤에 연결을 닫고 돌아온다 - 그 프레임의 알림이 풀 비우기 전에 풀에 들어가게")
    void 처리_중이던_프레임이_끝날_때까지_stop이_기다린다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean handled = new AtomicBoolean();
        AtomicBoolean handledWhenClosed = new AtomicBoolean();
        Thread frame = startBlockedFrame(release, handled);
        doAnswer(invocation -> {
            handledWhenClosed.set(handled.get());
            return null;
        }).when(session).close(CloseStatus.NORMAL);

        CompletableFuture<Void> stop = CompletableFuture.runAsync(subscriber::stop);
        try {
            assertThatThrownBy(() -> stop.get(300, TimeUnit.MILLISECONDS))
                    .as("프레임이 끝나기 전에는 stop이 돌아오지 않는다").isInstanceOf(TimeoutException.class);
            verify(session, never()).close(any(CloseStatus.class));
        } finally {
            release.countDown();
        }
        stop.get(5, TimeUnit.SECONDS);
        frame.join(5_000);

        assertThat(handledWhenClosed).as("연결을 닫는 시점에 프레임 처리는 끝나 있다").isTrue();
    }

    @Test
    @DisplayName("프레임이 마감을 넘기면 기다리기를 그만두고 닫는다 - 두 번째 stop(@PreDestroy)은 다시 기다리지 않는다")
    void 마감을_넘기면_닫고_다시_기다리지_않는다() throws Exception {
        subscriber.inFlightWait = Duration.ofMillis(300);
        CountDownLatch release = new CountDownLatch(1);
        Thread frame = startBlockedFrame(release, new AtomicBoolean());
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            long started = System.nanoTime();
            subscriber.stop();
            Duration first = Duration.ofNanos(System.nanoTime() - started);
            subscriber.stop();   // 빈 파괴 때 한 번 더 - 프레임은 아직 처리 중이다

            assertThat(first).isBetween(Duration.ofMillis(300), Duration.ofSeconds(3));
            verify(session, atLeastOnce()).close(CloseStatus.NORMAL);
            assertThat(shutdownLogs(logs))
                    .as("마감 초과 WARN은 한 번 - 두 번째 stop이 또 기다렸다면 두 번 남는다")
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("마감 안에 끝내지 못함", "limitMs=300", "처리 중=1");
                    });
        } finally {
            detachLogs(logs);
            release.countDown();
            frame.join(5_000);
        }
    }

    @Test
    @DisplayName("처리 중인 프레임이 없으면 stop은 기다리지 않는다 - 끝난 프레임(실패 포함)은 세지 않는다")
    void 처리_중_프레임이_없으면_기다리지_않는다() throws Exception {
        subscriber.handleTextMessage(session, new TextMessage("{\"type\":\"pong\"}"));
        subscriber.handleTextMessage(session, new TextMessage("not-json"));   // 처리에 실패한 프레임
        assertThat(inFlightFrames()).as("끝난 프레임은 성공·실패 모두 finally에서 내린다(U-3)").isZero();
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            long started = System.nanoTime();
            subscriber.stop();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(shutdownLogs(logs)).as("기다리지 않았으면 대기 로그도 없다").isEmpty();
        } finally {
            detachLogs(logs);
        }
    }

    private int inFlightFrames() {
        Object counter = org.springframework.test.util.ReflectionTestUtils.getField(subscriber, "inFlightFrames");
        return ((java.util.concurrent.atomic.AtomicInteger) counter).get();
    }

    @Test
    @DisplayName("처리 중 프레임 대기의 기본값은 예산에 들어가는 상수이고, Redis 명령 제한(2초)보다 길다")
    void 처리_중_프레임_대기_기본값() {
        assertThat(subscriber.inFlightWait)
                .isEqualTo(Duration.ofSeconds(AiLiveStreamSubscriber.IN_FLIGHT_WAIT_SECONDS));
        // Redis가 느린 순간에는 쿨다운 확인이 2초 뒤 fail-open으로 넘어가 저장한다 - 그 프레임까지 덮어야 한다.
        assertThat(AiLiveStreamSubscriber.IN_FLIGHT_WAIT_SECONDS).isGreaterThan(2);
    }

    @Test
    @DisplayName("종료 절차에서 우리가 닫은 정상 종료(code=1000)만 INFO다 - 운영 중 끊김과 종료 중 비정상 종료는 WARN")
    void 연결_종료_로그_레벨() {
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            subscriber.afterConnectionClosed(session, CloseStatus.NORMAL);           // 운영 중: AI가 먼저 닫음
            subscriber.stop();
            subscriber.afterConnectionClosed(session, CloseStatus.NO_CLOSE_FRAME);   // 종료 중이지만 비정상
            subscriber.afterConnectionClosed(session, CloseStatus.NORMAL);           // 종료 중 정상

            assertThat(logs.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("[ANOMALY] AI WS 연결 종료: code="))
                    .map(ILoggingEvent::getLevel))
                    .containsExactly(Level.WARN, Level.WARN, Level.INFO);
        } finally {
            detachLogs(logs);
        }
    }

    // ── 예외 원문을 로그에 남기지 않는다 (2026-11-22 점검 T-4) ─────────────────────────────

    @Test
    @DisplayName("전송 실패·전송 오류 로그에는 보낸 내용과 예외 메시지를 남기지 않는다 - 동작 이름과 예외 클래스명만")
    void 전송_실패_로그에_원문을_남기지_않는다() throws Exception {
        when(cameraService.findOwnerBySessionId(SESSION_ID))
                .thenReturn(Optional.of(new CameraOwner("WD0001", "거실")));
        doThrow(new IOException("peer=10.0.0.9 key=secret")).when(session).sendMessage(any());
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            subscriber.resync();                                               // 목록 재요청 실패
            subscriber.handleTextMessage(session, liveStreams(SESSION_ID));    // 구독 요청 실패
            subscriber.handleTransportError(session, new IOException("key=secret"));

            List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).contains(
                    "[ANOMALY] AI WS 전송 실패: action=list, error=IOException",
                    "[ANOMALY] AI WS 전송 실패: action=subscribe, error=IOException",
                    "[ANOMALY] AI WS 전송 오류: error=IOException");
            assertThat(messages).noneMatch(text -> text.contains("secret") || text.contains("\"action\""));
        } finally {
            detachLogs(logs);
        }
    }

    @Test
    @DisplayName("접속 실패 요약: 가장 안쪽 원인의 클래스명과 핸드셰이크 거절 상태 코드 3자리만 꺼낸다 - 메시지의 나머지는 버린다")
    void 접속_실패_요약() {
        Throwable rejected = new CompletionException(new DeploymentException(
                "The HTTP response from the server [401] did not permit the HTTP upgrade to WebSocket"));
        Throwable refused = new CompletionException(new DeploymentException(
                "The HTTP request to initiate the WebSocket connection to [wss://ai.example/ws?key=secret] failed",
                new ExecutionException(new ConnectException("Connection refused"))));

        assertThat(AiLiveStreamSubscriber.rootCauseName(rejected)).isEqualTo("DeploymentException");
        assertThat(AiLiveStreamSubscriber.handshakeStatus(rejected)).isEqualTo("401");
        assertThat(AiLiveStreamSubscriber.rootCauseName(refused)).isEqualTo("ConnectException");
        assertThat(AiLiveStreamSubscriber.rootCauseName(new RuntimeException() { }))
                .as("익명 클래스는 단순 이름이 비어 있어 전체 이름으로 대신한다(U-8)")
                .isNotEmpty().contains("AiLiveStreamSubscriberTest$");
        assertThat(AiLiveStreamSubscriber.handshakeStatus(refused)).isEqualTo("-");
        assertThat(AiLiveStreamSubscriber.handshakeStatus(new IllegalStateException())).as("메시지 없음").isEqualTo("-");
        assertThat(AiLiveStreamSubscriber.handshakeStatus(new IOException("port [8443], wait [999], [40]")))
                .as("HTTP 상태 코드 꼴(100~599, 대괄호 안 3자리)이 아니면 꺼내지 않는다").isEqualTo("-");
    }

    /** {@code latest_analysis} 1건을 다른 스레드에서 처리시키고 판정 서비스 안에서 붙잡아 둔다. 판정에 들어간 뒤 돌아온다. */
    private Thread startBlockedFrame(CountDownLatch release, AtomicBoolean handled) throws Exception {
        AnomalySignal signal = new AnomalySignal(SESSION_ID, DetectedType.FIRE, 0.9, true, null);
        CountDownLatch entered = new CountDownLatch(1);
        when(signalParser.parse(any())).thenReturn(Optional.of(signal));
        when(signalParser.expand(any(), eq(signal))).thenReturn(List.of(signal));
        when(detectionService.handle(signal)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            handled.set(true);
            return Optional.empty();
        });
        Thread frame = new Thread(
                () -> subscriber.handleTextMessage(session, new TextMessage("{\"type\":\"latest_analysis\"}")),
                "test-ai-frame");
        frame.setDaemon(true);
        frame.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("프레임이 판정에 들어갔다").isTrue();
        return frame;
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        subscriberLogger().addAppender(appender);
        return appender;
    }

    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        subscriberLogger().detachAppender(appender);
    }

    private static Logger subscriberLogger() {
        return (Logger) LoggerFactory.getLogger(AiLiveStreamSubscriber.class);
    }

    private static List<ILoggingEvent> shutdownLogs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("[SHUTDOWN-AI-STREAM]"))
                .toList();
    }
}
