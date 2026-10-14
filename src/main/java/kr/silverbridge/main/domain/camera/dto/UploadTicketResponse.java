package kr.silverbridge.main.domain.camera.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 송출(업로드) 1회용 티켓. */
@Schema(description = "영상 송출(WebSocket) 티켓")
public record UploadTicketResponse(
        @Schema(description = "uploadUrl의 ticket 쿼리에 붙이는 값(1회용, 60초)", example = "ut_Qm9vYmFy...")
        String ticket,
        @Schema(description = "남은 유효 시간(초)", example = "60")
        long expiresInSeconds,
        @Schema(description = "송출 WebSocket 주소(공개 wss 경로, 쿼리 없음). `?ticket=`을 붙여 연결한다",
                example = "wss://api.devdmu.gosky.kr/ws/camera-upload")
        String uploadUrl
) {}
