package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "새 비밀번호 설정 요청 (이메일/SMS 방식 공통)")
public class PasswordResetConfirmRequest {

    @Schema(description = "재설정 링크 URL의 token 쿼리 파라미터 값. 예: https://dmu.gosky.kr/reset-password?token={이 값}", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotBlank(message = "재설정 토큰을 입력해주세요.")
    private String token;

    @Schema(description = "새 비밀번호 (숫자·특수문자 포함, 공백 없이 8자 이상). 현재 비밀번호 및 최근 사용한 비밀번호 2개는 사용 불가", example = "NewPassword1!")
    @NotBlank(message = "새 비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다.")
    @Pattern(
            regexp = "^(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])\\S+$",
            message = "비밀번호는 숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다."
    )
    private String newPassword;
}
