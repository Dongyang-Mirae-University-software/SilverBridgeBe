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
    /** 연결을 해제한 주체 - 수신자에게 표시할 메시지 분기에 사용 */
    public enum DisconnectedBy {
        GUARDIAN,
        WARD,
        /** 관리자가 강제 해제. "보호자가 해제했습니다"로 내보내면 사실과 다르다. */
        ADMIN
    }
}
