package kr.silverbridge.main.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "비밀번호 변경 요청 (로그인 상태 전용)")
public class PasswordChangeRequest {

    @Schema(description = "현재 비밀번호", example = "OldPassword1!")
    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
    private String currentPassword;

    @Schema(description = "새 비밀번호 (숫자·특수문자 포함, 공백 없이 8자 이상). 현재 비밀번호와 동일 불가.", example = "NewPassword1!")
    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다.")
    @Pattern(
            regexp = "^(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])\\S+$",
            message = "비밀번호는 숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다."
    )
    private String newPassword;
}
