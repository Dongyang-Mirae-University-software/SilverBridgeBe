package kr.silverbridge.main.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.validation.ValidPassword;
import lombok.Getter;

@Getter
@Schema(description = "비밀번호 변경 요청 (로그인 상태 전용)")
public class PasswordChangeRequest {

    @Schema(description = "현재 비밀번호 (최대 64자)", example = "OldPassword1!")
    @NotBlank(message = "현재 비밀번호를 입력해주세요.")
    @Size(max = 64, message = "비밀번호는 64자 이내여야 합니다.")
    private String currentPassword;

    @Schema(description = "새 비밀번호 (영문·숫자·특수문자 포함, 공백 없이 8~64자). 현재 비밀번호와 동일 불가.", example = "NewPassword1!")
    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 8, max = 64, message = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8~64자여야 합니다.")
    @ValidPassword
    private String newPassword;
}
