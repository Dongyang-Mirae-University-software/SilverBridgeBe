package kr.silverbridge.main.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 피보호자 말벗 설정 변경. 보낸 항목만 바꾼다(null = 그대로). 둘 다 없으면 400. */
public record CheckinWardSettingRequest(
        @Schema(description = "표정 분석 동의. 꺼져 있으면 사진을 보내도 AI로 넘기지 않는다") Boolean faceConsent,
        @Schema(description = "하루 다섯 번 안부 알림 받기(본인이 끌 수 있다)") Boolean promptEnabled) {
}
