package kr.silverbridge.main.domain.notification.dto;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 알림 이력 필터(카테고리·기간)와 종류 라벨. */
class AdminNotificationFilterTest {

    @Test
    @DisplayName("모든 알림 종류는 정확히 한 카테고리에 속한다 - 새 종류가 어느 탭에도 안 보이는 일이 없다")
    void 카테고리_전수_분할() {
        Set<NotificationType> covered = EnumSet.noneOf(NotificationType.class);
        for (AdminNotificationCategory category : AdminNotificationCategory.values()) {
            Set<NotificationType> types = category.types();
            assertThat(java.util.Collections.disjoint(types, covered)).as("%s와 다른 카테고리가 겹침", category).isTrue();
            covered.addAll(types);
        }
        assertThat(covered).containsExactlyInAnyOrder(NotificationType.values());

        Arrays.stream(NotificationType.values()).forEach(type ->
                assertThat(AdminNotificationCategory.of(type).types()).contains(type));
    }

    @Test
    @DisplayName("기타 탭은 판정 요청·연결·문의다(SOS·이상감지·복약 밖의 전부)")
    void 기타_탭_구성() {
        assertThat(AdminNotificationCategory.OTHER.types()).containsExactlyInAnyOrder(
                NotificationType.ANOMALY_REVIEW_REQUIRED, NotificationType.ANOMALY_REVIEW_CONFLICTED,
                NotificationType.CONNECTION_REQUEST, NotificationType.CONNECTION_ACCEPTED,
                NotificationType.CONNECTION_REFUSED, NotificationType.CONNECTION_DISCONNECTED,
                NotificationType.CONNECTION_FORCED, NotificationType.INQUIRY_ANSWERED);
    }

    @Test
    @DisplayName("본인 대피 안내(ANOMALY_DETECTED_SELF)도 이상감지 탭이다")
    void 본인수신분도_이상감지() {
        assertThat(AdminNotificationCategory.of(NotificationType.ANOMALY_DETECTED_SELF))
                .isEqualTo(AdminNotificationCategory.ANOMALY);
        assertThat(NotificationTypeLabel.of(NotificationType.ANOMALY_DETECTED_SELF)).isEqualTo("이상감지");
    }

    @Test
    @DisplayName("기간은 오늘을 포함해 KST 자정부터 센다 - UTC 저녁은 KST 다음 날이다")
    void 기간_KST_경계() {
        // 2026-09-21 16:30 UTC = 2026-09-22 01:30 KST
        OffsetDateTime now = OffsetDateTime.of(2026, 9, 21, 16, 30, 0, 0, ZoneOffset.UTC);

        assertThat(AdminNotificationPeriod.TODAY.startFrom(now))
                .isEqualTo(OffsetDateTime.of(2026, 9, 22, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(AdminNotificationPeriod.LAST_7_DAYS.startFrom(now))
                .isEqualTo(OffsetDateTime.of(2026, 9, 16, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(AdminNotificationPeriod.LAST_30_DAYS.startFrom(now))
                .isEqualTo(OffsetDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("기간 생략은 최근 7일(프로토타입 기본값)")
    void 기간_기본값() {
        assertThat(AdminNotificationPeriod.orDefault(null)).isEqualTo(AdminNotificationPeriod.LAST_7_DAYS);
    }
}
