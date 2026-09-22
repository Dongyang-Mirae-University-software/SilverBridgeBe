package kr.silverbridge.main.domain.connection.event;

/**
 * ACTIVE 상태였던 연결이 해제된 직후 발행되는 이벤트
 * 해제를 수행하지 않은 반대편 당사자에게 WebSocket + FCM 알림을 발송한다.
 *
 * <p>{@code guardianId}·{@code wardId}는 <b>해제된 연결의 당사자</b>다(알림 대상은 {@code notifyTargetId}).
 * 관리자 알림 이력의 "피보호자" 칸에 쓰이고, 해제된 보호자의 이상감지 판정 표를 다시 계산할 때
 * (정책 "알려진 한계 - 판정 집계" E-3) 필요한 값이라 함께 싣는다. 탈퇴 경로에서는 연결 행이 곧 purge되므로
 * 소비자가 연결을 다시 조회하지 않아도 되게 한다.</p>
 */
public record ConnectionDisconnectedEvent(
        Long connectionId,
        String notifyTargetId,
        DisconnectedBy disconnectedBy,
        String guardianId,
        String wardId
) {
    /** 연결을 해제한 주체 - 수신자에게 표시할 메시지 분기에 사용 */
    public enum DisconnectedBy {
        GUARDIAN,
        WARD,
        /** 관리자가 강제 해제. "보호자가 해제했습니다"로 내보내면 사실과 다르다. */
        ADMIN
    }
}
