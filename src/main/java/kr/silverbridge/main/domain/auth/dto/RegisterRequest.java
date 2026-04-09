package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.enums.Role;
import lombok.Getter;

@Getter
public class RegisterRequest {

    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다.")
    @Pattern(
            regexp = "^(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])\\S+$",
            message = "비밀번호는 숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다."
    )
    private String password;

    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    @Pattern(
            regexp = "^\\d{10,11}$",
            message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)"
    )
    private String phone;

    // WARD(피보호자) 또는 GUARDIAN(보호자) 중 하나 필수 선택
    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;
}
