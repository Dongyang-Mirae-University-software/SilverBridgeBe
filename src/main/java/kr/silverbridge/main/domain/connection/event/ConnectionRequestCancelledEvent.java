package kr.silverbridge.main.domain.connection.event;

/**
 * 보호자가 수락 전 연결 요청을 취소한 직후 발행되는 이벤트 (2026-10-01 QA BE-3).
 *
 * <p><b>화면 갱신 신호 전용이다 - 알림이 아니다.</b> 요청 취소는 상대에게 알리지 않는다는 비대칭 정책(2026-05-28)은
 * 그대로다: 푸시·FCM·알림 이력을 만들지 않고 피보호자에게 WebSocket {@code connection-request-cancelled}만 보낸다.
 * 이 신호가 없으면 피보호자가 열어 둔 "요청 온 목록"에 취소된 요청이 남아, 누르면 오류가 났다.</p>
 *
 * @param wardId 요청을 받았던 피보호자(WS 수신자)
 */
public record ConnectionRequestCancelledEvent(
        Long connectionId,
        String wardId
) {}
