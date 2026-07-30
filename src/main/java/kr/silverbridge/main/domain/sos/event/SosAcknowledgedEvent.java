package kr.silverbridge.main.domain.sos.event;

import kr.silverbridge.main.domain.sos.entity.SosAckStatus;

/**
 * 보호자가 SOS 이력에 처리 결과(ACK)를 남겼을 때 발행되는 이벤트.
 *
 * <p>기록 트랜잭션 커밋 후 {@code SosAckNotificationListener}가 같은 피보호자의 다른 보호자들과 피보호자 본인의
 * 화면을 WebSocket으로 갱신한다. 푸시·문자는 보내지 않는다 — 긴급 상황이 끝난 뒤의 상태 갱신이라 알림 소음이다.</p>
 *
 * @param sosEventId    처리된 SOS 이력 ID
 * @param wardId        해당 이력의 피보호자 ID (수신자 도출용)
 * @param guardianId    처리한 보호자 ID
 * @param guardianName  처리한 보호자 이름 (이름이 비어 있으면 "보호자" 폴백이 들어온다)
 * @param ackStatus     처리 결과
 */
public record SosAcknowledgedEvent(
        Long sosEventId,
        String wardId,
        String guardianId,
        String guardianName,
        SosAckStatus ackStatus
) {}
