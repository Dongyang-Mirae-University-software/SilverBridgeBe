package kr.silverbridge.main.domain.anomaly.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 이상감지 수신 설정 (application.yaml {@code anomaly.*}).
 *
 * <p>{@code apiKey}는 환경변수({@code AI_API_KEY})로만 주입한다 — 코드·문서 평문 금지. 키가 비어 있으면
 * 앱을 죽이지 않고 <b>구독만 비활성화</b>한다(로컬 개발 편의 + "WS 연결 실패가 기동을 막지 않는다" 규칙).</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "anomaly")
public class AnomalyProperties {

    /**
     * 이상감지 판정 기준.
     *
     * <ul>
     *   <li>{@link #DANGER} (기본) — AI가 채운 {@code danger} 플래그만 신뢰한다. 위험 판정 책임 = AI 서버.</li>
     *   <li>{@link #CONFIDENCE} (폴백) — {@code confidence >= confidenceThreshold}로 백엔드가 판정.
     *       AI의 danger 정식화 배포가 늦어져 이력이 0건이 되는 공백을 임시로 메울 때만 쓴다.</li>
     * </ul>
     */
    public enum TriggerMode {
        DANGER,
        CONFIDENCE
    }

    /** 이상감지 수신 기능 전체 ON/OFF. false면 AI WS에 접속하지 않는다. */
    private boolean enabled = true;

    /** AI 이상감지 WebSocket 주소. */
    private String wsUrl = "wss://testai.gosky.kr/api/v1/ws/live";

    /** AI 서버 API Key. x-api-key 헤더로 전송(쿼리 방식은 URL 로깅에 노출되므로 사용하지 않는다). */
    private String apiKey = "";

    /** 판정 모드. 기본 DANGER(AI 판정 신뢰). */
    private TriggerMode triggerMode = TriggerMode.DANGER;

    /** CONFIDENCE 폴백 모드에서 이상감지로 인정할 최소 신뢰도. AI 표시용 임계(0.35)보다 높게 잡는다. */
    private double confidenceThreshold = 0.6;

    /**
     * 같은 (sessionId, detectedType) 이력 적재 최소 간격(분). 매 프레임 broadcast로 인한 이력 폭주를 막는다.
     *
     * <p>알림 간격보다 <b>짧게</b> 잡는다 — 이력은 촘촘할수록 "언제부터 언제까지 감지됐는지" 타임라인이
     * 남아 사후 분석에 쓸모가 있고, 저장 비용도 작다. 사람에게 가는 빈도는 아래 알림 쿨다운이 따로 정한다.</p>
     */
    private long cooldownMinutes = 1;

    /**
     * 보호자에게 같은 감지를 다시 알리기까지의 최소 간격(분). alarm fatigue 방지.
     *
     * <p>보호자는 폰으로 받는다 — FCM뿐 아니라 <b>알림톡·SMS도 이 간격으로 나가므로</b> 과금과
     * 카카오 신고·발신 프로필 차단 리스크가 함께 걸린다. 이력 쿨다운과 분리해 두는 이유다.</p>
     */
    private long notifyCooldownMinutes = 5;

    /**
     * 피보호자 본인에게 다시 알리기까지의 최소 간격(분). 현장 당사자라 보호자보다 짧게 잡는다.
     *
     * <p>1분이 아니라 3분인 이유: 오탐이 났을 때 24시간 켜져 있는 피보호자 화면에 알림이 1분마다 쌓이면
     * 소음이 된다. 대피 재촉이라는 목적(D-1)은 보호자보다 짧다는 것으로 충분히 달성된다.</p>
     */
    private long notifySelfCooldownMinutes = 3;

    /**
     * 연속 감지를 같은 "상황(incident)"으로 묶는 최대 간격(분).
     *
     * <p>이력 쿨다운(1분)보다 훨씬 길게 잡는다 - 화재가 잠깐 잦아들었다 다시 잡히는 구간까지 한 사건으로
     * 봐야 보호자가 같은 불을 여러 번 판정하지 않는다. 기준을 짧게 잡으면 통계의 "이상감지 건수"가
     * 실제 사건 수보다 부풀고, 길게 잡으면 별개 사건이 합쳐져 줄어든다.</p>
     */
    private long incidentMergeMinutes = 10;

    /** 재연결 백오프 최소 간격(초). */
    private long reconnectMinSeconds = 2;

    /** 재연결 백오프 최대 간격(초). */
    private long reconnectMaxSeconds = 60;
}
