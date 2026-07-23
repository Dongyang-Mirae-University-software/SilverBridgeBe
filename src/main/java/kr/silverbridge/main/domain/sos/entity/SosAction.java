package kr.silverbridge.main.domain.sos.entity;

/**
 * 피보호자가 긴급 SOS 버튼을 눌렀을 때의 동작 방식(환경설정 &gt; SOS 동작 설정).
 *
 * <p><b>이 설정이 정하는 것은 "119를 어떻게 연결·안내할지"뿐이다.</b> 실제 119 전화 연결은 프론트가
 * {@code tel:119} 링크로 처리하며 백엔드는 관여하지 않는다(기존 {@code WardSosController} 안내와 동일).
 * 백엔드는 값을 계정에 보관·제공하기만 한다.</p>
 *
 * <p><b>보호자 알림은 어떤 값에서도 항상 발송된다.</b> SOS는 생명과 직결되므로
 * {@code NotificationType.WARD_SOS}가 사용자 설정을 무시하고 강제 발송하는 필수 알림이고,
 * 이 설정으로 끌 수 없다. 따라서 세 값의 차이는 "보호자 알림 여부"가 아니라 <b>119 연결 시점·안내 방식</b>이다.
 * 값 이름의 {@code CALL_119}는 "보호자 알림 없이"라는 뜻이 아님에 주의한다.</p>
 *
 * <p>값 이름은 프론트가 쓰던 키({@code call119}, {@code call119AndNotify}, {@code notifyGuardianFirst})와
 * 1:1로 대응시켜 화면 라벨만 교체하면 되도록 했다.</p>
 */
public enum SosAction {

    /** 119 즉시 연결 — 버튼을 누르면 곧바로 119 연결로 이동한다(보호자 알림도 함께 발송). */
    CALL_119,

    /** 119 연결 + 보호자 알림 안내 — 119로 연결하면서 "보호자에게도 알렸다"는 안내를 함께 보여준다. */
    CALL_119_AND_NOTIFY,

    /** 보호자에게 먼저 알린 뒤 119 안내 — 알림 발송 완료 화면에서 119 연결 방법을 안내한다. */
    NOTIFY_GUARDIAN_FIRST
}
