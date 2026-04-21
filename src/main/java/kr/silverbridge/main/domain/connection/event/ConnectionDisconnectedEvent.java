package kr.silverbridge.main.domain.connection.event;

/**
 * ACTIVE 상태였던 연결이 해제된 직후 발행되는 이벤트
 * 해제를 수행하지 않은 반대편 당사자에게 WebSocket + FCM 알림을 발송한다.
 */
public record ConnectionDisconnectedEvent(
        Long connectionId,
        String notifyTargetId,
        DisconnectedBy disconnectedBy
) {
    /** 연결을 해제한 당사자 역할 — 수신자에게 표시할 메시지 분기에 사용 */
    public enum DisconnectedBy {
        GUARDIAN,
        WARD
    }
}
