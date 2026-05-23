package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "[SMS 방식] 비밀번호 재설정 인증코드 발송 요청")
public class PasswordResetSmsSendRequest {

    @Schema(description = "가입 시 입력한 이름 (최대 20자)", example = "홍길동")
    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
    private String name;

    @Schema(description = "가입 시 입력한 전화번호 (숫자만, 하이픈 없이 10~11자리). 일치하는 계정이 없으면 404, 카카오로 가입한 계정은 400으로 안내합니다. (2026-05-23 정책 변경)", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{10,11}$", message = "올바른 전화번호 형식이 아닙니다. (숫자 10~11자리, 하이픈 없이)")
    private String phone;
}
