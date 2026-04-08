package kr.silverbridge.main.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.Utf8ByteLength;
import lombok.Getter;

@Getter
public class PasswordChangeRequest {

    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 8, max = 24, message = "비밀번호는 8자 이상 24자 이하여야 합니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z가-힣])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9가-힣!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
            message = "비밀번호는 영문 또는 한글, 숫자, 특수문자만 사용할 수 있으며 각 종류를 1개 이상 포함해야 하고 공백을 사용할 수 없습니다."
    )
    @Utf8ByteLength(
            max = 72,
            message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다."
    )
    private String newPassword;
}
