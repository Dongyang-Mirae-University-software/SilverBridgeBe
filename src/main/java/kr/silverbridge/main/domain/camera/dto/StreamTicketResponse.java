package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 영상(MJPEG) 1회용 티켓. */
@Schema(description = "영상 스트림 티켓")
public record StreamTicketResponse(
        @Schema(description = "영상 주소의 ticket 쿼리에 붙이는 값(1회용)", example = "st_Qm9vYmFy...")
        String ticket,
        @Schema(description = "남은 유효 시간(초)", example = "60")
        long expiresInSeconds
) {}
