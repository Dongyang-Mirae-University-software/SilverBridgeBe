package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "보호자 알림 종류별 수신 설정")
public record GuardianNotificationTypeSettingResponse(
        @Schema(description = "SOS 알림 - 필수, 항상 켜짐(변경 불가)") TypeSetting sos,
        @Schema(description = "이상감지(화재·흉기·낙상) 발생 알림 - 필수, 항상 켜짐(변경 불가). 푸시는 어떤 설정으로도 줄지 않는다")
        TypeSetting anomalyDetection,
        @Schema(description = "이상감지 판정 확인 요청(재촉) 알림. 끌 수 있다") TypeSetting anomalyReviewReminder,
        @Schema(description = "복약 미복용 요약 알림. 끌 수 있다(피보호자별 설정과 둘 다 켜져야 발송)") TypeSetting medication) {

    @Schema(description = "종류별 설정")
    public record TypeSetting(
            @Schema(description = "수신 여부", example = "true") boolean enabled,
            @Schema(description = "true면 필수 알림이라 변경할 수 없다(FE는 잠금 표시)", example = "false") boolean required) {

        public static TypeSetting locked() {
            return new TypeSetting(true, true);
        }

        public static TypeSetting optional(boolean enabled) {
            return new TypeSetting(enabled, false);
        }
    }
}
