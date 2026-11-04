package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 변경 가능한 종류만 받는다. {@code null}은 "변경하지 않음". 필수 알림(sos·anomalyDetection)이나
 * 정서 변화·병원 예약 키를 보내도 무시된다(저장하지 않음).
 */
@Schema(description = "보호자 알림 종류별 수신 설정 변경 (null = 변경 안 함)")
public record GuardianNotificationTypeSettingRequest(
        @Schema(description = "판정 확인 요청(재촉) 수신 여부", example = "false") Boolean anomalyReviewReminder,
        @Schema(description = "복약 알림 수신 여부 - 내 미복용 요약과 피보호자의 \"약 드실 시간\" 알림을 함께 켜고 끈다", example = "false") Boolean medication,
        @Schema(description = "카메라 연결 끊김 안내 수신 여부", example = "false") Boolean cameraLinkLost,
        @Schema(description = "안부 말벗 주의 알림(걱정·우울이 3일 이상 이어짐) 수신 여부", example = "false")
        Boolean companionNotice) {

    /** 말벗 주의 알림을 바꾸지 않는 요청(2026-11-04 이전 키 3개). */
    public GuardianNotificationTypeSettingRequest(Boolean anomalyReviewReminder, Boolean medication,
                                                  Boolean cameraLinkLost) {
        this(anomalyReviewReminder, medication, cameraLinkLost, null);
    }
}
