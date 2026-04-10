package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
@Schema(description = "SMS 인증코드 확인 요청")
public class SmsVerifyRequest {

    @Schema(description = "인증코드를 받은 전화번호 (숫자만, 하이픈 없이 10~11자리)", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)")
    private String phone;

    @Schema(description = "SMS로 받은 6자리 인증코드", example = "123456")
    @NotBlank(message = "인증코드를 입력해주세요.")
    private String code;
}
