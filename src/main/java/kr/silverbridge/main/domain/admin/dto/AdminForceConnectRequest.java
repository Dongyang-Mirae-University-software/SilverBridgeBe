package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "관리자 강제 연결 요청")
public class AdminForceConnectRequest {

    @Schema(description = "보호자 사용자 UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    @NotBlank(message = "보호자 ID를 입력해주세요.")
    private String guardianId;

    @Schema(description = "피보호자 사용자 UUID", example = "b2c3d4e5-f6a7-8901-bcde-f12345678901")
    @NotBlank(message = "피보호자 ID를 입력해주세요.")
    private String wardId;
}
