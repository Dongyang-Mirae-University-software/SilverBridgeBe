package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.validation.Utf8ByteLength;
import lombok.Getter;

@Getter
public class RegisterRequest {

    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, max = 24, message = "비밀번호는 8자 이상 24자 이하여야 합니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z가-힣])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9가-힣!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
            message = "비밀번호는 영문 또는 한글, 숫자, 특수문자만 사용할 수 있으며 각 종류를 1개 이상 포함해야 하고 공백을 사용할 수 없습니다."
    )
    @Utf8ByteLength(
            max = 72,
            message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다."
    )
    private String password;

    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    private String phone;

    // WARD(피보호자) 또는 GUARDIAN(보호자) 중 하나 필수 선택
    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;
}
