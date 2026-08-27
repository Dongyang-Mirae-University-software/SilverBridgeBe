package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalTime;

/**
 * 보호자가 특정 피보호자에 대해 받을 미복용 요약 설정 변경 요청.
 *
 * <p>{@code null}은 "변경하지 않음"이다(설정 API 공통 규약).</p>
 */
@Schema(description = "보호자 미복용 요약 수신 설정 변경 요청 (피보호자별)")
public record GuardianMedicationAlertSettingRequest(

        @Schema(description = "이 피보호자가 복약을 체크하지 않은 날 요약을 받을지 (생략 시 기존값 유지)",
                example = "true")
        Boolean missedAlertEnabled,

        @Schema(description = """
                요약을 받을 시각(KST, 분 단위 · 생략 시 기존값 유지).
                지정한 시각까지 복용 시각이 지난 약만 집계되며, 그 이후에 먹는 약은 그날 요약에 포함되지 않습니다.
                기본값(21:00)으로 되돌리려면 21:00을 직접 지정하세요.""",
                example = "21:00:00", type = "string")
        LocalTime missedAlertTime
) {}
