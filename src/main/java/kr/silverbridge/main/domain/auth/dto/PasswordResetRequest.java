package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "[이메일 방식] 비밀번호 재설정 이메일 발송 요청")
public class PasswordResetRequest {

    @Schema(description = "가입 시 사용한 이메일 주소. 보안상 이메일 존재 여부와 관계없이 항상 200을 반환합니다.", example = "user@example.com")
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;
}
