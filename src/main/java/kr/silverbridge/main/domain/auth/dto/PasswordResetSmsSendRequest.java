package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
@Schema(description = "[SMS 방식] 비밀번호 재설정 인증코드 발송 요청")
public class PasswordResetSmsSendRequest {

    @Schema(description = "가입 시 입력한 이름", example = "홍길동")
    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    @Schema(description = "가입 시 입력한 전화번호 (숫자만, 하이픈 없이 10~11자리). 보안상 일치하는 계정이 없어도 항상 200을 반환합니다.", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자 10~11자리여야 합니다.")
    private String phone;
}
