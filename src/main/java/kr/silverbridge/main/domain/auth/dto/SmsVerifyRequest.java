package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class SmsVerifyRequest {

    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)")
    private String phone;

    @NotBlank(message = "인증코드를 입력해주세요.")
    private String code;
}
