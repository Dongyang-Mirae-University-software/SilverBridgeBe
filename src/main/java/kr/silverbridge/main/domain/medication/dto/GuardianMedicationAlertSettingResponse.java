package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보호자 본인의 복약 알림 수신 설정. 저장된 행이 없으면 기본값(ON)이 담긴다.
 */
@Schema(description = "보호자 복약 알림 수신 설정")
public record GuardianMedicationAlertSettingResponse(

        @Schema(description = "미복용 요약 알림 수신 여부", example = "true")
        boolean missedAlertEnabled
) {
    public static GuardianMedicationAlertSettingResponse of(boolean missedAlertEnabled) {
        return new GuardianMedicationAlertSettingResponse(missedAlertEnabled);
    }
}
