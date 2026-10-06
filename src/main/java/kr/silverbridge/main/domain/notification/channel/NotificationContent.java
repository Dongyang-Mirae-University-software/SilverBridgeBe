package kr.silverbridge.main.domain.notification.channel;

import java.util.Map;

/**
 * 채널에 전달되는 알림 내용.
 *
 * <p>{@code title}/{@code body}는 사람이 읽는 문구, {@code data}는 클라이언트가 파싱하는 부가 정보
 * (예: {@code type}, {@code connectionId}). 채널별로 사용하는 필드가 다르다 — FCM은 셋 모두,
 * SMS는 {@code title}/{@code body}만 사용한다. {@code smsFallbackText}는 문자 대체 발송 전용 선택 문구다.</p>
 *
 * @param data null 또는 빈 맵 허용. 채널 구현체는 null-safe 하게 처리한다.
 */
public record NotificationContent(
        String title,
        String body,
        Map<String, String> data,
        String smsFallbackText
) {
    public static NotificationContent of(String title, String body, Map<String, String> data) {
        return new NotificationContent(title, body, data, null);
    }

    /**
     * 푸시 미전달로 문자가 <b>대체 발송</b>될 때만 쓰는 문구가 있는 알림. 문자 길이 안에서 푸시 본문과 다르게
     * 쓰고 싶을 때(이상감지) 지정한다. 사용자가 켠 문자 채널과 SOS 폴백은 이 값을 읽지 않는다.
     */
    public static NotificationContent of(String title, String body, Map<String, String> data,
                                         String smsFallbackText) {
        return new NotificationContent(title, body, data, smsFallbackText);
    }
}
