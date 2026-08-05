package kr.silverbridge.main.domain.medication.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보호자 본인의 복약 알림 수신 설정 변경 요청.
 *
 * <p>{@code null}은 "변경하지 않음"이다(설정 API 공통 규약).</p>
 */
@Schema(description = "보호자 복약 알림 수신 설정 변경 요청")
public record GuardianMedicationAlertSettingRequest(

        @Schema(description = "피보호자가 복약을 체크하지 않은 날 저녁 요약을 받을지 (생략 시 기존값 유지)",
                example = "true")
        Boolean missedAlertEnabled
) {}
