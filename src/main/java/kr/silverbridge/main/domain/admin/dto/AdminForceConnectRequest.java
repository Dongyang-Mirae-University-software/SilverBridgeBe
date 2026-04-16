package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "관리자 강제 연결 요청")
public class AdminForceConnectRequest {

    @Schema(description = "보호자 사용자 ID", example = "aB3x9Z")
    @NotBlank(message = "보호자 ID를 입력해주세요.")
    private String guardianId;

    @Schema(description = "피보호자 사용자 ID", example = "cD4y0W")
    @NotBlank(message = "피보호자 ID를 입력해주세요.")
    private String wardId;
}
