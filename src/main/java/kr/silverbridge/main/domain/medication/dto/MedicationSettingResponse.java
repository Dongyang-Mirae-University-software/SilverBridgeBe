package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.service.MedicationPreference;

/**
 * 복약 알림 설정 응답. 저장된 행이 없으면 기본값(둘 다 켜짐)이 담긴다.
 */
@Schema(description = "복약 알림 설정")
public record MedicationSettingResponse(

        @Schema(description = "피보호자 ID", example = "A1B2C3")
        String wardId,

        @Schema(description = "복약 알림 사용 여부", example = "true")
        boolean alarmEnabled,

        @Schema(description = "체크하지 않았을 때 한 번 더 알릴지", example = "true")
        boolean remindAgainEnabled
) {
    public static MedicationSettingResponse of(String wardId, MedicationPreference preference) {
        return new MedicationSettingResponse(wardId, preference.alarmEnabled(), preference.remindAgainEnabled());
    }
}
