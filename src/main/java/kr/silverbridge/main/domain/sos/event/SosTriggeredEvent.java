package kr.silverbridge.main.domain.sos.event;

/**
 * 피보호자가 긴급 SOS를 발생시켰을 때 발행되는 이벤트.
 *
 * <p>이력(sos_events) 저장 트랜잭션 커밋 후 {@code SosNotificationListener}가 ACTIVE 보호자 전원에게
 * 긴급 알림을 발송한다. 발송 실패가 이력 저장을 롤백시키지 않도록 AFTER_COMMIT에서 처리된다.</p>
 *
 * @param wardId     SOS를 발생시킨 피보호자 ID
 * @param sosEventId 저장된 sos_events 행 ID (클라이언트 상관관계용)
 * @param wardName   피보호자 이름 (알림 문구 "{이름}님이 긴급 도움을 요청했습니다."에 사용)
 */
public record SosTriggeredEvent(
        String wardId,
        Long sosEventId,
        String wardName
) {}
