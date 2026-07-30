package kr.silverbridge.main.domain.sos.entity;

/**
 * 보호자가 SOS 이력에 남기는 처리 결과.
 *
 * <p>"미처리"는 별도 값이 아니라 {@code ack_status IS NULL}로 표현한다 — NULL과 중복되는 상태값을 두지 않아
 * 기존 이력 백필이 필요 없다({@code SosEvent#isAcknowledged()}로 판별).</p>
 *
 * <p><b>ACK는 사후 기록일 뿐 알림 발송에 개입하지 않는다.</b> SOS 보호자 알림은
 * {@code NotificationType#WARD_SOS}(필수 알림)로 항상 발송되며, 이미 처리된 SOS라도 알림 정책은 바뀌지 않는다.</p>
 */
public enum SosAckStatus {

    /** 안전 확인 — 통화·방문 등으로 피보호자가 무사한 것을 확인했다. */
    SAFE_CONFIRMED,

    /** 응급 출동 — 119 출동·병원 이송 등 실제 응급 대응이 이루어졌다. */
    EMERGENCY_DISPATCHED
}
