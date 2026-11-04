package kr.silverbridge.main.domain.notification.dto;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 사용자 알림 묶음 매핑 - 모든 알림 종류가 정확히 한 묶음에 속하고, 긴급 묶음은 사용자 결정대로다.
 *
 * <p>{@code of()}의 switch에는 default가 없어 새 종류는 컴파일에서 먼저 막힌다. 이 테스트는 누가 default를 넣어
 * 우회했을 때와 묶음 간 겹침을 잡는다.</p>
 */
class UserNotificationCategoryTest {

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("모든 NotificationType은 묶음이 정해져 있다")
    void 모든_종류_매핑(NotificationType type) {
        assertThatNoException().isThrownBy(() -> UserNotificationCategory.of(type));
        assertThat(UserNotificationCategory.of(type)).isNotNull();
        assertThat(UserNotificationCategory.of(type).types()).contains(type);
    }

    @Test
    @DisplayName("묶음들의 종류 집합은 겹치지 않고 합치면 전 종류다")
    void 분할() {
        Set<NotificationType> union = EnumSet.noneOf(NotificationType.class);
        int total = 0;
        for (UserNotificationCategory category : UserNotificationCategory.values()) {
            union.addAll(category.types());
            total += category.types().size();
        }
        assertThat(union).containsExactlyInAnyOrder(NotificationType.values());
        assertThat(total).isEqualTo(NotificationType.values().length);
    }

    @Test
    @DisplayName("URGENT = SOS·이상감지 발생(보호자·본인)·말벗 위험 신호 / OTHER에 재촉·카메라·문의·말벗 안내")
    void 묶음_내용() {
        assertThat(UserNotificationCategory.URGENT.types()).containsExactlyInAnyOrder(
                NotificationType.WARD_SOS, NotificationType.ANOMALY_DETECTED,
                NotificationType.ANOMALY_DETECTED_SELF, NotificationType.COMPANION_RISK);
        assertThat(UserNotificationCategory.MEDICATION.types()).containsExactlyInAnyOrder(
                NotificationType.MEDICATION_REMINDER, NotificationType.MEDICATION_MISSED,
                NotificationType.MEDICATION_STOPPED);
        assertThat(UserNotificationCategory.CONNECTION.types()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(NotificationType.values()).filter(t -> t.name().startsWith("CONNECTION_")).toList());
        assertThat(UserNotificationCategory.OTHER.types()).contains(
                NotificationType.ANOMALY_REVIEW_REQUIRED, NotificationType.CAMERA_LINK_LOST,
                NotificationType.INQUIRY_ANSWERED, NotificationType.COMPANION_PROMPT, NotificationType.COMPANION_NOTICE);
    }
}
