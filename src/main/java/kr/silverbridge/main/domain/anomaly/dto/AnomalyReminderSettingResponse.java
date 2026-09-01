package kr.silverbridge.main.domain.anomaly.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 재촉 수신 설정 현재값. 저장된 행이 없으면 기본값(ON)이 실린다. */
@Schema(description = "이상감지 판정 재촉 수신 설정")
public record AnomalyReminderSettingResponse(

        @Schema(description = "재촉 수신 여부", example = "true")
        boolean reviewReminderEnabled) {
}
