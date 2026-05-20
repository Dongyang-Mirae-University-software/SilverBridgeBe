package kr.silverbridge.main.domain.connection.event;

/**
 * 보호자가 피보호자에게 연결 요청을 보낸 직후 발행되는 이벤트
 * 피보호자 앱에 WebSocket + FCM 알림을 발송하기 위해 사용된다.
 *
 * relation: 보호자가 입력한 피보호자와의 관계(예: "아들"). 신규 요청에서는 항상 non-null,
 *           기존 코드 호환을 위해 null이면 fallback 문구로 발송한다.
 */
public record ConnectionRequestedEvent(
        Long connectionId,
        String guardianId,
        String wardId,
        String guardianName,
        String relation
) {}
