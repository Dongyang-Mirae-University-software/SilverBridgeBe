package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = """
        새 비밀번호 설정 요청 (이메일/SMS 방식 공통).
        '인증코드 사전 확인'(/find-password/email|sms/verify) 단계에서 검증한 그 6자리 코드를 그대로 다시 전달합니다. (UUID 토큰 없음)
        이메일 방식이면 email을, SMS 방식이면 phone을 채워 보냅니다. (둘 중 정확히 하나)
        """)
public class PasswordResetConfirmRequest {

    @Schema(description = "[이메일 방식] 인증코드를 받은 이메일 (최대 50자). SMS 방식이면 null/생략.",
            example = "user@example.com", nullable = true)
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 50, message = "이메일은 50자 이내여야 합니다.")
    private String email;

    @Schema(description = "[SMS 방식] 인증코드를 받은 전화번호(숫자만, 하이픈 없이). 이메일 방식이면 null/생략.",
            example = "01012345678", nullable = true)
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자 10~11자리여야 합니다.")
    private String phone;

    @Schema(description = "메일/SMS로 받은 6자리 숫자 인증코드 (사전 확인 단계에서 입력한 값과 동일)", example = "123456")
    @NotBlank(message = "인증코드를 입력해주세요.")
    @Pattern(regexp = "^\\d{6}$", message = "인증코드는 숫자 6자리여야 합니다.")
    private String code;

    @Schema(description = "새 비밀번호 (영문·숫자·특수문자 모두 포함, 공백 없이 8~64자). 현재 비밀번호와 동일 불가",
            example = "NewPassword1!")
    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 8, max = 64, message = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8~64자여야 합니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
            message = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다."
    )
    private String newPassword;
}
