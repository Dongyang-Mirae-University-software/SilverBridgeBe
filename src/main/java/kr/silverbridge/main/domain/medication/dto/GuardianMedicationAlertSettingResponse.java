package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.medication.service.GuardianMissedAlertSetting;

import java.time.LocalTime;

/**
 * 보호자 본인의 복약 알림 수신 설정. 저장된 행이 없으면 기본값(ON · 21:00)이 담긴다.
 *
 * <p>시각은 언제나 <b>실효값</b>이다 - 미설정 여부를 노출하지 않고 "지금 몇 시에 받는지"만 답한다.</p>
 */
@Schema(description = "보호자 복약 알림 수신 설정")
public record GuardianMedicationAlertSettingResponse(

        @Schema(description = "미복용 요약 알림 수신 여부", example = "true")
        boolean missedAlertEnabled,

        @Schema(description = "요약을 받는 시각(KST). 이 시각까지 예정된 약만 집계됩니다.",
                example = "21:00:00", type = "string")
        LocalTime missedAlertTime
) {
    public static GuardianMedicationAlertSettingResponse from(GuardianMissedAlertSetting setting) {
        return new GuardianMedicationAlertSettingResponse(setting.enabled(), setting.alertTime());
    }
}
