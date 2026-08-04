package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 복약 알림 설정 응답. 저장된 행이 없으면 기본값(ON)이 담긴다.
 */
@Schema(description = "복약 알림 설정")
public record MedicationSettingResponse(

        @Schema(description = "피보호자 ID", example = "A1B2C3")
        String wardId,

        @Schema(description = "복약 알림 사용 여부", example = "true")
        boolean alarmEnabled
) {
    public static MedicationSettingResponse of(String wardId, boolean alarmEnabled) {
        return new MedicationSettingResponse(wardId, alarmEnabled);
    }
}
