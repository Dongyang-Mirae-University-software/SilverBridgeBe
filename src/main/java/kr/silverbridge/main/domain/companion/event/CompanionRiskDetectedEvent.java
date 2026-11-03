package kr.silverbridge.main.domain.companion.event;

/**
 * 안부 대화에서 위험 신호가 처음 나왔다(대화당 1번 - {@code risk_notified_at} 선점에 이긴 쪽만 발행한다).
 * 대답 내용은 싣지 않는다 - 보호자 알림에도 원문·질병명을 넣지 않는다(결정 B·K).
 */
public record CompanionRiskDetectedEvent(String wardId, long sessionId) {
}
