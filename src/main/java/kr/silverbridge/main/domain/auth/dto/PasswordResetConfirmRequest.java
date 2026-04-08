package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import kr.silverbridge.main.global.validation.Utf8ByteLength;
import lombok.Getter;

@Getter
public class PasswordResetConfirmRequest {

    @NotBlank(message = "재설정 토큰을 입력해주세요.")
    private String token;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Pattern(
            regexp = "^(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])\\S{8,}$",
            message = "비밀번호는 8자 이상, 숫자·특수문자를 포함해야 하며 공백을 사용할 수 없습니다."
    )
    @Utf8ByteLength(
            max = 72,
            message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다. 한글/이모지는 더 짧아도 제한에 걸릴 수 있습니다."
    )
    private String newPassword;
}
