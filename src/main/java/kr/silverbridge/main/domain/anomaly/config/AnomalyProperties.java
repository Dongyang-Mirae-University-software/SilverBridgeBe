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

    /** 같은 (sessionId, detectedType) 이력 적재 최소 간격(분). 매 프레임 broadcast로 인한 이력 폭주를 막는다. */
    private long cooldownMinutes = 5;

    /** 보호자에게 같은 감지를 다시 알리기까지의 최소 간격(분). alarm fatigue 방지. */
    private long notifyCooldownMinutes = 5;

    /** 피보호자 본인에게 다시 알리기까지의 최소 간격(분). 현장 당사자라 대피 재촉을 위해 더 짧게 잡는다. */
    private long notifySelfCooldownMinutes = 1;

    /** 재연결 백오프 최소 간격(초). */
    private long reconnectMinSeconds = 2;

    /** 재연결 백오프 최대 간격(초). */
    private long reconnectMaxSeconds = 60;
}
