package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "[이메일 방식] 비밀번호 재설정 이메일 발송 요청")
public class PasswordResetRequest {

    @Schema(description = "가입 시 사용한 이메일 주소 (최대 50자). 미가입 이메일은 404, 카카오로 가입한 계정은 400으로 안내합니다. (2026-05-23 정책 변경)", example = "user@example.com")
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    @Size(max = 50, message = "이메일은 50자 이내여야 합니다.")
    private String email;
}
