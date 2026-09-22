package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 관리자 알림 이력의 탭(카테고리) 필터.
 *
 * <p>{@link #OTHER}는 나머지 셋에 속하지 않는 전 종류다 - 알림 종류가 새로 생겨도 어느 탭에도 안 보이는
 * 일이 없도록 목록이 아니라 여집합으로 정한다.</p>
 */
@Schema(description = "알림 카테고리 (생략 시 전체) - SOS · ANOMALY 이상감지 · MEDICATION 복약 · OTHER 기타(판정 요청·연결·문의)")
public enum AdminNotificationCategory {

    SOS(EnumSet.of(NotificationType.WARD_SOS)),
    ANOMALY(EnumSet.of(NotificationType.ANOMALY_DETECTED, NotificationType.ANOMALY_DETECTED_SELF)),
    MEDICATION(EnumSet.of(NotificationType.MEDICATION_REMINDER, NotificationType.MEDICATION_MISSED,
            NotificationType.MEDICATION_STOPPED)),
    OTHER(null);

    private final Set<NotificationType> explicitTypes;

    AdminNotificationCategory(Set<NotificationType> explicitTypes) {
        this.explicitTypes = explicitTypes;
    }

    /** 이 카테고리에 속하는 알림 종류. */
    public Set<NotificationType> types() {
        if (explicitTypes != null) {
            return explicitTypes;
        }
        Set<NotificationType> rest = EnumSet.allOf(NotificationType.class);
        Arrays.stream(values())
                .filter(category -> category.explicitTypes != null)
                .forEach(category -> rest.removeAll(category.explicitTypes));
        return rest;
    }

    public static AdminNotificationCategory of(NotificationType type) {
        return Arrays.stream(values())
                .filter(category -> category.explicitTypes != null && category.explicitTypes.contains(type))
                .findFirst()
                .orElse(OTHER);
    }
}
