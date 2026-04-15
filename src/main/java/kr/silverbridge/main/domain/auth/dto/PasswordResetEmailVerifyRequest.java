package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "[이메일 방식] 비밀번호 재설정 토큰 검증 요청")
public class PasswordResetEmailVerifyRequest {

    @Schema(description = "이메일로 수신한 비밀번호 재설정 토큰", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotBlank(message = "토큰을 입력해주세요.")
    private String token;
}
