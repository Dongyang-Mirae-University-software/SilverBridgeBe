package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "[SMS 방식] 비밀번호 재설정 코드 응답")
public class PasswordResetTokenResponse {

    @Schema(description = "비밀번호 재설정 코드. POST /api/auth/password/reset 의 token 필드에 전달. 유효 시간: 30분", example = "550e8400-e29b-41d4-a716-446655440000")
    private String token;
}
