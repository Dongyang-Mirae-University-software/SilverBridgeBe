package kr.silverbridge.main.domain.notification;

import kr.silverbridge.main.domain.notification.config.FcmMessageProperties;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.dto.AdminNotificationCategory;
import kr.silverbridge.main.domain.notification.dto.NotificationTypeLabel;
import kr.silverbridge.main.domain.notification.dto.UserNotificationCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [가드] 말벗 여러 날 위험 누적({@code COMPANION_RISK_PATTERN})은 공용 경로에서 위험 신호({@code COMPANION_RISK})와
 * 똑같이 다뤄진다(2026-11-11 사용자 결정, 영향 범위 점검 C-3). 한쪽만 바꾸면 이 테스트가 깨진다 - 의도된 분리면 결정을
 * 남기고 이 테스트를 고칠 것.
 */
class CompanionRiskTypeParityTest {

    private static final NotificationType RISK = NotificationType.COMPANION_RISK;
    private static final NotificationType PATTERN = NotificationType.COMPANION_RISK_PATTERN;

    @Test
    @DisplayName("채널 정책·허용 채널(이메일 없음)이 같다")
    void sameDispatchPolicy() {
        assertThat(PATTERN.policy()).isEqualTo(RISK.policy());
        assertThat(PATTERN.allowedChannels()).isEqualTo(RISK.allowedChannels());
    }

    @Test
    @DisplayName("사용자 묶음·관리자 탭·종류 이름이 같다")
    void sameCategoriesAndLabel() {
        assertThat(UserNotificationCategory.of(PATTERN)).isEqualTo(UserNotificationCategory.of(RISK));
        assertThat(AdminNotificationCategory.of(PATTERN)).isEqualTo(AdminNotificationCategory.of(RISK));
        assertThat(NotificationTypeLabel.of(PATTERN)).isEqualTo(NotificationTypeLabel.of(RISK));
    }

    @Test
    @DisplayName("웹푸시 기본 high 목록에 둘 다 있다")
    void bothHighUrgency() {
        FcmMessageProperties properties = new FcmMessageProperties();
        assertThat(properties.isHighUrgency(PATTERN)).isTrue();
        assertThat(properties.isHighUrgency(RISK)).isTrue();
    }
}
