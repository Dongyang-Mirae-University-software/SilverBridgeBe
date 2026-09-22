package kr.silverbridge.main.domain.notification.entity;

import kr.silverbridge.main.domain.notification.channel.ChannelFailureReason;
import kr.silverbridge.main.domain.notification.channel.ChannelResult;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;

/**
 * 채널 1건 시도 결과. {@code notification_log.channel_results}(JSONB)의 원소다.
 *
 * <p>시도한 채널만 담는다 - {@link ChannelResult.Status#NOT_APPLICABLE}(알림톡 템플릿 미매핑 등)은
 * 원래 대상이 아니므로 기록하지 않는다.</p>
 *
 * @param reason 실패일 때만 값이 있다.
 */
public record ChannelAttempt(NotificationChannelType channel, ChannelResult.Status status,
                             ChannelFailureReason reason) {

    public static ChannelAttempt of(NotificationChannelType channel, ChannelResult result) {
        return new ChannelAttempt(channel, result.status(), result.reason());
    }

    public boolean delivered() {
        return status == ChannelResult.Status.DELIVERED;
    }
}
