package kr.silverbridge.main.domain.notification.channel;

import java.util.Map;

/**
 * 채널에 전달되는 알림 내용.
 *
 * <p>{@code title}/{@code body}는 사람이 읽는 문구, {@code data}는 클라이언트가 파싱하는 부가 정보
 * (예: {@code type}, {@code connectionId}). 채널별로 사용하는 필드가 다르다 — FCM은 셋 모두,
 * SMS는 {@code title}/{@code body}만 사용한다.</p>
 *
 * @param data null 또는 빈 맵 허용. 채널 구현체는 null-safe 하게 처리한다.
 */
public record NotificationContent(
        String title,
        String body,
        Map<String, String> data
) {
    public static NotificationContent of(String title, String body, Map<String, String> data) {
        return new NotificationContent(title, body, data);
    }
}
