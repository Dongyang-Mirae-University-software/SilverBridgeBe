package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사용자 알림 기록(종 아이콘)의 화면 필터 묶음 (2026-11-04).
 *
 * <p>관리자 탭({@link AdminNotificationCategory})과는 따로 둔다 - 관리자는 SOS·이상감지를 나눠 보고, 사용자 화면은
 * "급한 것 / 복약 / 연결 / 그 밖"으로 본다. 한쪽을 바꿔도 다른 쪽 화면이 흔들리지 않게 한다.</p>
 *
 * <p>매핑은 {@link #of(NotificationType)}의 {@code switch} 한 곳뿐이고 {@code default}를 두지 않는다 - 알림 종류가 새로
 * 생기면 여기서 컴파일이 멈춰, 묶음을 정하지 않은 채 배포되는 일이 없다.</p>
 */
@Schema(description = """
        알림 묶음 (생략 시 전체)
        - URGENT: SOS · 이상감지 발생(보호자·본인) · 안부 말벗 위험 신호
        - MEDICATION: 복약 시각 · 미복용 요약 · 복약 일정 중지
        - CONNECTION: 연결 요청 · 수락 · 거절 · 해제 · 관리자 연결
        - OTHER: 이상감지 판정 요청 · 판정 재확인 · 카메라 연결 확인 · 문의 답변 · 안부 말벗 안내·기분 변화""")
public enum UserNotificationCategory {

    URGENT,
    MEDICATION,
    CONNECTION,
    OTHER;

    public static UserNotificationCategory of(NotificationType type) {
        return switch (type) {
            case WARD_SOS, ANOMALY_DETECTED, ANOMALY_DETECTED_SELF, COMPANION_RISK -> URGENT;
            case MEDICATION_REMINDER, MEDICATION_MISSED, MEDICATION_STOPPED -> MEDICATION;
            case CONNECTION_REQUEST, CONNECTION_ACCEPTED, CONNECTION_REFUSED, CONNECTION_DISCONNECTED,
                 CONNECTION_FORCED -> CONNECTION;
            case ANOMALY_REVIEW_REQUIRED, ANOMALY_REVIEW_CONFLICTED, CAMERA_LINK_LOST, INQUIRY_ANSWERED,
                 COMPANION_PROMPT, COMPANION_NOTICE -> OTHER;
        };
    }

    /** 이 묶음에 속하는 알림 종류(목록 쿼리의 IN 절). */
    public Set<NotificationType> types() {
        return Arrays.stream(NotificationType.values())
                .filter(type -> of(type) == this)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationType.class)));
    }
}
