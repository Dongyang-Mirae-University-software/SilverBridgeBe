package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.global.enums.Status;

/**
 * 알림 수신자 식별 정보. 채널마다 필요한 식별자가 다르므로 한 번 조회해 함께 전달한다.
 *
 * <ul>
 *   <li>{@code userId} — FCM(토큰 조회 키), 모든 채널 공통 로깅 키</li>
 *   <li>{@code phone}  — SMS / 카카오 알림톡(2단계)</li>
 *   <li>{@code email}  — 이메일(3단계)</li>
 * </ul>
 *
 * <p>{@code phone}/{@code email}은 사용자가 미입력했을 수 있어 null 가능. 해당 채널 구현체가
 * null 여부를 확인한 뒤 발송한다.</p>
 *
 * <p>{@code status}는 <b>발송 여부 판단용</b>이다(채널이 쓰지 않는다). 사용자 행을 찾지 못하면
 * {@code null}이며, 이때는 상태를 모르는 것이지 정지된 것이 아니므로 발송을 막지 않는다.</p>
 */
public record NotificationRecipient(
        String userId,
        String phone,
        String email,
        Status status
) {
    /** 알림을 받을 수 있는 상태인가. 상태를 알 수 없으면(행 없음) 기존 동작대로 발송을 허용한다. */
    public boolean canReceive() {
        return status == null || status == Status.ACTIVE;
    }
}
