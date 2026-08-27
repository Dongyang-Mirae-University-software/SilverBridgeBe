package kr.silverbridge.main.domain.sos.entity;

/**
 * SOS 이력의 발생 경로 - 피보호자가 SOS 화면에서 무엇을 눌러 이력이 생겼는지.
 *
 * <p>두 경로 모두 <b>이력을 남기고 보호자 알림도 동일하게 발송된다</b>. 구분하는 이유는 보호자 이력 화면에서
 * "긴급 SOS를 눌렀다"와 "나에게 전화를 걸었다"가 다르게 읽혀야 하기 때문이다 - 알림 발송 여부를 가르는
 * 값이 아니므로 {@code SosNotificationListener}·{@code NotificationDispatcher}가 이 값을 읽게 만들지 말 것
 * (SOS 동작 설정 2026-07-23, ACK 2026-07-30 규칙의 연장 - 필수 알림에 조건을 붙이지 않는다).</p>
 */
public enum SosTriggerType {

    /** 긴급 SOS 버튼 - 피보호자가 SOS 화면의 큰 버튼을 눌렀다. 값이 없을 때의 기본값이다. */
    SOS_BUTTON,

    /** 보호자에게 직접 전화 - 피보호자가 SOS 화면에서 보호자 카드를 골라 전화를 걸었다. */
    GUARDIAN_CALL
}
