package kr.silverbridge.main.domain.notification.channel;

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
 */
public record NotificationRecipient(
        String userId,
        String phone,
        String email
) {}
