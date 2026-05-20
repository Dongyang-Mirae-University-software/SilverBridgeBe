package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "로그인 요청")
public class LoginRequest {

    @Schema(description = "가입한 이메일 주소 (최대 50자)", example = "user@example.com")
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 50, message = "이메일은 50자 이내여야 합니다.")
    private String email;

    @Schema(description = "비밀번호 (8~64자)", example = "Password1!")
    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(max = 64, message = "비밀번호는 64자 이내여야 합니다.")
    private String password;
}
