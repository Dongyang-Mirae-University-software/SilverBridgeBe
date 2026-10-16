package kr.silverbridge.main.domain.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 이메일 허용 종류 고정(2026-10-13). 새 종류가 조용히 메일로 나가거나 긴급 알림이 메일로 번지는 것을 막는다. */
class NotificationTypeEmailPolicyTest {

    private static final Set<NotificationType> EMAIL_ALLOWED = EnumSet.of(
            NotificationType.CONNECTION_REQUEST,
            NotificationType.CONNECTION_ACCEPTED,
            NotificationType.CONNECTION_REFUSED,
            NotificationType.CONNECTION_DISCONNECTED,
            NotificationType.CONNECTION_FORCED,
            NotificationType.INQUIRY_ANSWERED,
            NotificationType.MEDICATION_MISSED);

    @Test
    @DisplayName("이메일은 안내성 종류로 명시한 것만 허용한다 - 나머지는 모두 제외")
    void 허용_종류_고정() {
        Set<NotificationType> actual = EnumSet.noneOf(NotificationType.class);
        Arrays.stream(NotificationType.values())
                .filter(t -> t.allowedChannels().contains(NotificationChannelType.EMAIL))
                .forEach(actual::add);

        assertThat(actual).isEqualTo(EMAIL_ALLOWED);
    }

    @Test
    @DisplayName("긴급 종류(SOS·이상감지·본인분)와 반복 알림(복약 알림·재촉)은 이메일을 쓰지 않는다")
    void 긴급_종류_제외() {
        for (NotificationType type : EnumSet.of(NotificationType.WARD_SOS, NotificationType.ANOMALY_DETECTED,
                NotificationType.ANOMALY_DETECTED_SELF, NotificationType.MEDICATION_REMINDER,
                NotificationType.ANOMALY_REVIEW_REQUIRED, NotificationType.ANOMALY_REVIEW_CONFLICTED,
                NotificationType.CAMERA_LINK_LOST)) {
            assertThat(type.allowedChannels()).as(type.name()).doesNotContain(NotificationChannelType.EMAIL);
        }
    }

    @Test
    @DisplayName("강제 정책 종류는 어떤 경우에도 이메일을 허용하지 않는다")
    void 강제정책은_이메일_불가() {
        Arrays.stream(NotificationType.values())
                .filter(t -> t.policy() != NotificationType.Policy.SETTINGS_ONLY)
                .forEach(t -> assertThat(t.allowedChannels()).as(t.name())
                        .doesNotContain(NotificationChannelType.EMAIL));
    }

    @Test
    @DisplayName("이메일을 뺀 다른 채널(FCM·SMS·알림톡)의 기존 허용 범위는 그대로다")
    void 다른_채널_무변경() {
        assertThat(NotificationType.CONNECTION_REQUEST.allowedChannels()).containsExactlyInAnyOrder(
                NotificationChannelType.FCM, NotificationChannelType.SMS,
                NotificationChannelType.KAKAO_ALIMTALK, NotificationChannelType.EMAIL);
        assertThat(NotificationType.MEDICATION_REMINDER.allowedChannels()).containsExactlyInAnyOrder(
                NotificationChannelType.FCM, NotificationChannelType.SMS,
                NotificationChannelType.KAKAO_ALIMTALK);
        assertThat(NotificationType.ANOMALY_REVIEW_REQUIRED.allowedChannels())
                .containsExactly(NotificationChannelType.FCM);
    }
}
