package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 피보호자 말벗 설정. 요청·응답에 같이 쓴다. */
public record CheckinSettingDto(
        @Schema(description = "표정 분석 동의(기본 false). 꺼져 있으면 사진을 보내도 AI로 넘기지 않는다")
        @NotNull Boolean faceConsent) {
}
