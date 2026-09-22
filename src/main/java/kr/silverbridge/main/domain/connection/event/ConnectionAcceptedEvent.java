package kr.silverbridge.main.domain.connection.event;

/**
 * 피보호자가 연결 요청을 수락한 직후 발행되는 이벤트
 * 보호자 앱에 WebSocket + FCM 알림을 발송하기 위해 사용된다.
 *
 * @param wardId 수락한 피보호자. 알림 대상이 아니라 관리자 알림 이력의 "피보호자" 칸에 쓰인다.
 */
public record ConnectionAcceptedEvent(
        Long connectionId,
        String guardianId,
        String wardId
) {}
