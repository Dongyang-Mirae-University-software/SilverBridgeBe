package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 변경 가능한 종류만 받는다. {@code null}은 "변경하지 않음". 필수 알림(sos·anomalyDetection)이나
 * 정서 변화·병원 예약 키를 보내도 무시된다(저장하지 않음).
 */
@Schema(description = "보호자 알림 종류별 수신 설정 변경 (null = 변경 안 함)")
public record GuardianNotificationTypeSettingRequest(
        @Schema(description = "판정 확인 요청(재촉) 수신 여부", example = "false") Boolean anomalyReviewReminder,
        @Schema(description = "복약 미복용 요약 수신 여부", example = "false") Boolean medication) {
}
