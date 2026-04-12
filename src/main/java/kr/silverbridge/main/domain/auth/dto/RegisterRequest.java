package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.enums.Role;
import lombok.Getter;

@Getter
@Schema(description = "일반 회원가입 요청 (SMS 인증 완료 후 호출)")
public class RegisterRequest {

    @Schema(description = "이메일 주소", example = "user@example.com")
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @Schema(description = "비밀번호 (영문·숫자·특수문자 포함, 공백 없이 8자 이상)", example = "Password1!")
    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
            message = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다."
    )
    private String password;

    @Schema(description = "이름", example = "홍길동")
    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    @Schema(description = "전화번호 (숫자만, 하이픈 없이 10~11자리)", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(
            regexp = "^\\d{10,11}$",
            message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)"
    )
    private String phone;

    @Schema(description = "역할 선택. WARD: 피보호자, GUARDIAN: 보호자", example = "WARD", allowableValues = {"WARD", "GUARDIAN"})
    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;
}
