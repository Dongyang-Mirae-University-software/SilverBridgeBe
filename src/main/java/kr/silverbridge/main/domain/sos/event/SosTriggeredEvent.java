package kr.silverbridge.main.domain.sos.event;

import kr.silverbridge.main.domain.sos.entity.SosTriggerType;

/**
 * 피보호자가 긴급 SOS를 발생시켰을 때 발행되는 이벤트.
 *
 * <p>이력(sos_events) 저장 트랜잭션 커밋 후 {@code SosNotificationListener}가 ACTIVE 보호자 전원에게
 * 긴급 알림을 발송한다. 발송 실패가 이력 저장을 롤백시키지 않도록 AFTER_COMMIT에서 처리된다.</p>
 *
 * @param wardId      SOS를 발생시킨 피보호자 ID
 * @param sosEventId  저장된 sos_events 행 ID (클라이언트 상관관계용)
 * @param wardName    피보호자 이름 (알림 문구 "{이름}님이 긴급 도움을 요청했습니다."에 사용)
 * @param triggerType 저장된 발생 경로. <b>알림 문구 선택에만 쓴다</b>(2026-11-20 사용자 결정 - {@code CHATBOT_109}만
 *                    "마음 위기" 문구). 알림 여부·대상 보호자·채널·쿨다운을 이 값으로 가르지 말 것
 *                    (policy-sos 규칙 ②, {@code SosNotificationListenerTest}의 109 사례가 고정한다)
 */
public record SosTriggeredEvent(
        String wardId,
        Long sosEventId,
        String wardName,
        SosTriggerType triggerType
) {}
