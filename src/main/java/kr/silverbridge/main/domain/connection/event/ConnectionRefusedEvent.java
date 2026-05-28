package kr.silverbridge.main.domain.connection.event;

/**
 * 피보호자가 연결 요청을 거절한 직후 발행되는 이벤트
 * 요청을 보낸 보호자 앱에 WebSocket + FCM 알림을 발송하기 위해 사용된다.
 */
public record ConnectionRefusedEvent(
        Long connectionId,
        String guardianId
) {}
