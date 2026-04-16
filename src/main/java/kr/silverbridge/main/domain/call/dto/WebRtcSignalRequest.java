package kr.silverbridge.main.domain.call.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Schema(description = "WebRTC 시그널링 메시지")
@Getter
public class WebRtcSignalRequest {

    @Schema(description = "시그널 타입", allowableValues = {"offer", "answer", "ice-candidate"}, example = "offer")
    @NotBlank(message = "시그널 타입을 입력해주세요.")
    private String type;

    @Schema(description = "수신자 사용자 ID", example = "AB1234")
    @NotBlank(message = "수신자 ID를 입력해주세요.")
    private String targetId;

    @Schema(description = "SDP 또는 ICE candidate 데이터 (JSON string)", example = "{\"sdp\":\"...\",\"type\":\"offer\"}")
    @NotNull(message = "시그널 데이터를 입력해주세요.")
    private Object data;
}