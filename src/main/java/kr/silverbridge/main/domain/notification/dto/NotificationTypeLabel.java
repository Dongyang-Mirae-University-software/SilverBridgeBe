package kr.silverbridge.main.domain.notification.dto;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;

/**
 * 관리자 화면에 보이는 알림 종류 이름. 표시 문구를 서버 한 곳에 둔다.
 *
 * <p>switch가 전 값을 다루므로 {@link NotificationType}에 값을 더하면 여기서 컴파일이 깨진다 - 라벨 누락을 막는다.</p>
 */
public final class NotificationTypeLabel {

    private NotificationTypeLabel() {
    }

    public static String of(NotificationType type) {
        return switch (type) {
            case WARD_SOS -> "SOS";
            case ANOMALY_DETECTED, ANOMALY_DETECTED_SELF -> "이상감지";
            case ANOMALY_REVIEW_REQUIRED, ANOMALY_REVIEW_CONFLICTED -> "판정 요청";
            case MEDICATION_REMINDER, MEDICATION_MISSED, MEDICATION_STOPPED -> "복약";
            case CONNECTION_REQUEST, CONNECTION_ACCEPTED, CONNECTION_REFUSED, CONNECTION_DISCONNECTED,
                 CONNECTION_FORCED -> "연결";
            case INQUIRY_ANSWERED -> "문의";
        };
    }
}
