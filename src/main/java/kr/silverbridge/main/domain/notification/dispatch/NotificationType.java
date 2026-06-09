package kr.silverbridge.main.domain.notification.dispatch;

/**
 * 디스패처를 경유하는 알림 종류와 "필수/선택" 분류.
 *
 * <ul>
 *   <li><b>선택(mandatory=false)</b> — 사용자 알림 설정(채널 ON/OFF)을 따른다. 현재 연결 관련 알림 전부.</li>
 *   <li><b>필수(mandatory=true)</b> — 사용자 설정을 무시하고 강제 발송. 피보호자 긴급 SOS({@code WARD_SOS})가
 *       첫 사용처이며, 향후 이상감지 등 긴급 알림도 여기에 등록한다.</li>
 * </ul>
 *
 * <p>참고: SMS 인증번호(가입·비밀번호 재설정)는 애초에 디스패처를 경유하지 않고 동기 발송되므로,
 * "설정으로 끌 수 없음"이 구조적으로 보장된다 — 여기 필수 타입으로 둘 필요가 없다.</p>
 */
public enum NotificationType {
    CONNECTION_REQUEST(false),
    CONNECTION_ACCEPTED(false),
    CONNECTION_REFUSED(false),
    CONNECTION_DISCONNECTED(false),

    // 피보호자 긴급 SOS. 보호자가 알림을 꺼도 반드시 받아야 하는 긴급 알림이라 필수(mandatory=true)로 분류한다.
    WARD_SOS(true);

    private final boolean mandatory;

    NotificationType(boolean mandatory) {
        this.mandatory = mandatory;
    }

    /** true면 사용자 설정을 무시하고 강제 발송한다. */
    public boolean isMandatory() {
        return mandatory;
    }
}