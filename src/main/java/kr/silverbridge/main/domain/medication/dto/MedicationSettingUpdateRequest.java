package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 복약 알림 ON/OFF 변경 요청(보호자 화면의 토글).
 */
@Schema(description = "복약 알림 설정 변경 요청")
public record MedicationSettingUpdateRequest(

        @NotNull(message = "alarmEnabled는 필수입니다.")
        @Schema(description = "복약 알림 사용 여부", example = "true")
        Boolean alarmEnabled
) {}
