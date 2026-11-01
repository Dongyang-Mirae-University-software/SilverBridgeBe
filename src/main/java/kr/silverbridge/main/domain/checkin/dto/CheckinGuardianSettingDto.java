package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 보호자가 정하는 피보호자 안부 알림 받기. 요청·응답에 같이 쓴다. */
public record CheckinGuardianSettingDto(
        @Schema(description = "하루 다섯 번(09·12·15·18·21시) 피보호자에게 안부 알림 보내기(기본 false)")
        @NotNull Boolean promptEnabled) {
}
