package kr.silverbridge.main.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class PasswordChangeRequest {

    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
    private String currentPassword;

    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 8, max = 24, message = "비밀번호는 8자 이상 24자 이하여야 합니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z가-힣])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9가-힣!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
            message = "비밀번호는 영어, 한글, 숫자, 특수문자를 각각 하나 이상 포함해야 하며, 띄어쓰기는 사용할 수 없습니다."
    )
    private String newPassword;
}
