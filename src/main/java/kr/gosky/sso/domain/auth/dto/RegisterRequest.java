package kr.gosky.sso.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class RegisterRequest {

    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Pattern(
            regexp = "^(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])\\S{8,}$",
            message = "비밀번호는 8자 이상, 숫자·특수문자를 포함해야 하며 공백을 사용할 수 없습니다."
    )
    private String password;

    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    private String phone;
}
