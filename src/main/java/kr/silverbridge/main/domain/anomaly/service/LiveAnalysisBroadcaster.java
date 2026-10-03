package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.dto.AnomalySignal;
import kr.silverbridge.main.domain.anomaly.dto.DetectedTypeLabel;
import kr.silverbridge.main.domain.anomaly.dto.LiveAnalysisMessage;
import kr.silverbridge.main.domain.camera.dto.CameraLiveStatus;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.dto.LiveAnalysisSnapshot;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.camera.service.LiveAnalysisSnapshotPort;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * 카메라 실시간 분석 상태를 보호자 화면에 보낸다 - STOMP {@code /topic/{guardianId}/camera-analysis}(2026-10-03).
 *
 * <p>브라우저가 AI WebSocket에 직접 붙어 받던 것(AI 키가 브라우저에 노출됐다)을 대신한다. 백엔드는 이미 등록 카메라의
 * AI 분석을 구독하고 있으므로({@code AiLiveStreamSubscriber}) 그 신호를 화면용으로 한 번 더 흘려보낸다.</p>
 *
 * <ul>
 *   <li><b>상태가 바뀔 때만</b>(송출 상태·감지 종류·위험 여부) 보내고, 같은 카메라는 최소 2초 간격이다. AI는 프레임마다
 *       결과를 보내는데(초당 2회꼴) 발송 1건마다 수신자 상태 조회(정지 계정 게이트)가 따라붙어 그대로 흘리면 DB를 두드린다.</li>
 *   <li><b>WebSocket 전용</b>이다 - {@code NotificationDispatcher}를 거치지 않아 푸시·문자·알림 이력이 생기지 않는다.
 *       화재 알림은 판정을 거친 {@code anomaly-detected}가 따로 담당한다. 이 경로에 푸시를 붙이지 말 것.</li>
 *   <li>수신자는 그 카메라 피보호자의 <b>ACTIVE 보호자</b>뿐이다(영상과 같은 열람 범위). 발송은
 *       {@link WebSocketEventPublisher#sendToUser}로만 해 정지 계정 차단을 그대로 따른다.</li>
 *   <li>DB 조회·발송은 전용 풀({@code liveAnalysisExecutor})에서 한다 - AI 수신 스레드가 기다리면 화재 신호 처리가 밀린다.</li>
 * </ul>
 */
@Slf4j
@Component
public class LiveAnalysisBroadcaster implements LiveAnalysisSnapshotPort {

    static final String EVENT = "camera-analysis";
    static final long MIN_INTERVAL_MILLIS = 2_000;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CameraService cameraService;
    private final ConnectionService connectionService;
    private final WebSocketEventPublisher webSocketEventPublisher;
    private final Executor executor;
    private final LongSupplier clock;

    /** 세션별 마지막 상태. 등록 카메라 세션만 들어오므로 카메라 대수만큼만 쌓인다(종료 시 제거). */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Autowired
    public LiveAnalysisBroadcaster(CameraService cameraService,
                                   ConnectionService connectionService,
                                   WebSocketEventPublisher webSocketEventPublisher,
                                   @Qualifier("liveAnalysisExecutor") Executor executor) {
        this(cameraService, connectionService, webSocketEventPublisher, executor, System::currentTimeMillis);
    }

    LiveAnalysisBroadcaster(CameraService cameraService, ConnectionService connectionService,
                            WebSocketEventPublisher webSocketEventPublisher, Executor executor, LongSupplier clock) {
        this.cameraService = cameraService;
        this.connectionService = connectionService;
        this.webSocketEventPublisher = webSocketEventPublisher;
        this.executor = executor;
        this.clock = clock;
    }

    /** AI {@code latest_analysis} 수신. */
    public void onAnalysis(AnomalySignal signal) {
        update(signal.sessionId(), state -> state.withAnalysis(signal));
    }

    /** AI {@code session_status} 수신. */
    public void onSessionStatus(String sessionId, String aiStatus) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        update(sessionId, state -> state.withStatus(CameraLiveStatus.fromAi(aiStatus)));
    }

    /** AI 목록에서 사라진 세션 - 화면에 꺼짐을 알리고 상태를 지운다(간격 제한 없이 바로). */
    public void onSessionsEnded(Collection<String> sessionIds) {
        for (String sessionId : sessionIds) {
            State removed = states.remove(sessionId);
            if (removed != null && removed.sentKey() != null) {
                dispatch(sessionId, new State(CameraLiveStatus.OFFLINE, null, null, 0));
            }
        }
    }

    /**
     * AI 연결이 끊겼다 - 받아 둔 결과가 현재 상태라고 보장할 수 없어 비우고, 화면에 이미 상태를 보낸 세션에는
     * <b>"확인 불가"(status=null)</b>를 알린다(2026-10-03 점검 M-1). 비우기만 하면 재접속을 기다리는 동안 화면에
     * 마지막 "송출 중·정상"이 그대로 남아, 모르는 값을 아는 값처럼 보여준다(대시보드와 같은 원칙).
     */
    public void clear() {
        for (Map.Entry<String, State> entry : states.entrySet()) {
            if (states.remove(entry.getKey(), entry.getValue()) && entry.getValue().sentKey() != null) {
                dispatch(entry.getKey(), State.UNKNOWN);
            }
        }
    }

    @Override
    public Optional<LiveAnalysisSnapshot> findLatest(String sessionId) {
        State state = states.get(sessionId);
        if (state == null || state.analysis() == null) {
            return Optional.empty();
        }
        AnomalySignal a = state.analysis();
        return Optional.of(new LiveAnalysisSnapshot(a.detectedType().name(), DetectedTypeLabel.ofLive(a.detectedType()),
                a.confidence(), a.danger(), toKst(a.analyzedAt())));
    }

    private void update(String sessionId, UnaryOperator<State> change) {
        long now = clock.getAsLong();
        State[] toSend = {null};
        states.compute(sessionId, (key, current) -> {
            State next = change.apply(current == null ? State.EMPTY : current);
            String changeKey = next.changeKey();
            if (!changeKey.equals(next.sentKey()) && now - next.sentAt() >= MIN_INTERVAL_MILLIS) {
                next = next.markSent(changeKey, now);
                toSend[0] = next;
            }
            return next;
        });
        if (toSend[0] != null) {
            dispatch(sessionId, toSend[0]);
        }
    }

    private void dispatch(String sessionId, State state) {
        try {
            executor.execute(() -> fanOut(sessionId, state));
        } catch (RuntimeException e) {
            log.warn("[LIVE-ANALYSIS] 발송 작업 등록 실패 - 이번 상태 폐기: sessionId={}, error={}",
                    sessionId, e.getClass().getSimpleName());
        }
    }

    void fanOut(String sessionId, State state) {
        try {
            // 꺼진 카메라는 영상·목록과 같이 표시 대상이 아니다(2026-10-03 점검 L-1). 감지·화재 알림은 별개로 계속된다.
            Optional<CameraOwner> owner = cameraService.findActiveOwnerBySessionId(sessionId);
            if (owner.isEmpty()) {
                return;   // 삭제·비활성 카메라 - 보내지 않는다
            }
            String wardId = owner.get().wardId();
            List<String> guardianIds = connectionService.getActiveGuardianIds(wardId);
            if (guardianIds.isEmpty()) {
                return;
            }
            LiveAnalysisMessage message = toMessage(sessionId, wardId, state);
            for (String guardianId : guardianIds) {
                webSocketEventPublisher.sendToUser(guardianId, EVENT, message);
            }
        } catch (RuntimeException e) {
            log.warn("[LIVE-ANALYSIS] 실시간 분석 상태 발송 실패: sessionId={}, error={}",
                    sessionId, e.getClass().getSimpleName());
        }
    }

    private static LiveAnalysisMessage toMessage(String sessionId, String wardId, State state) {
        AnomalySignal a = state.analysis();
        if (a == null) {
            return new LiveAnalysisMessage(sessionId, wardId, state.status(), null, null, null, null, null);
        }
        OffsetDateTime analyzedAt = toKst(a.analyzedAt());
        return new LiveAnalysisMessage(sessionId, wardId, state.status(), a.detectedType().name(),
                DetectedTypeLabel.ofLive(a.detectedType()), a.confidence(), a.danger(),
                analyzedAt == null ? null : analyzedAt.toString());
    }

    private static OffsetDateTime toKst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toOffsetDateTime();
    }

    /**
     * 세션 1개의 상태(불변).
     *
     * @param sentKey 마지막으로 보낸 상태의 비교 키
     * @param sentAt  마지막 발송 시각(ms)
     */
    record State(String status, AnomalySignal analysis, String sentKey, long sentAt) {

        static final State EMPTY = new State(null, null, null, 0);
        /** AI 연결이 끊겨 상태를 알 수 없음 - status·분석 모두 null로 나간다. */
        static final State UNKNOWN = new State(null, null, null, 0);

        State withStatus(String newStatus) {
            return new State(newStatus, analysis, sentKey, sentAt);
        }

        // 분석 결과는 송출 중인 세션에만 온다 - 송출 상태를 아직 못 받았으면 running으로 둔다.
        // 그래야 메시지의 status=null이 "확인 불가"(AI 연결 끊김) 한 가지 뜻만 갖는다.
        State withAnalysis(AnomalySignal newAnalysis) {
            return new State(status == null ? CameraLiveStatus.RUNNING : status, newAnalysis, sentKey, sentAt);
        }

        State markSent(String key, long at) {
            return new State(status, analysis, key, at);
        }

        /** 화면에 의미 있는 변화만 비교한다 - 신뢰도·분석 시각은 매 프레임 바뀌어 넣지 않는다. */
        String changeKey() {
            String analysisKey = analysis == null ? "-" : analysis.detectedType() + "/" + analysis.danger();
            return status + "|" + analysisKey;
        }
    }
}
