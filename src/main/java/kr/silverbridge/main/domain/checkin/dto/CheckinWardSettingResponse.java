package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CheckinWardSettingResponse(
        @Schema(description = "표정 분석 동의(기본 false)") boolean faceConsent,
        @Schema(description = "하루 다섯 번 안부 알림 받기(기본 false - 보호자가 켠다)") boolean promptEnabled) {
}
