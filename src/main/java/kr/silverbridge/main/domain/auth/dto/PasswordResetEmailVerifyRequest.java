package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "[이메일 방식] 비밀번호 재설정 인증코드 확인 요청")
public class PasswordResetEmailVerifyRequest {

    @Schema(description = "비밀번호 재설정 인증코드를 발송한 이메일 주소", example = "user@example.com")
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @Schema(description = "이메일로 받은 6자리 인증코드", example = "123456")
    @NotBlank(message = "인증코드를 입력해주세요.")
    private String code;
}
