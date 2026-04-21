package kr.silverbridge.main.domain.connection.event;

/**
 * 보호자가 피보호자에게 연결 요청을 보낸 직후 발행되는 이벤트
 * 피보호자 앱에 WebSocket + FCM 알림을 발송하기 위해 사용된다.
 */
public record ConnectionRequestedEvent(
        Long connectionId,
        String guardianId,
        String wardId,
        String guardianName
) {}
