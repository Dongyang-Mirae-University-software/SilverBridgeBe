package kr.silverbridge.main.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import kr.silverbridge.main.global.enums.Role;
import lombok.Getter;

@Getter
public class KakaoRegisterRequest {

    @NotBlank(message = "카카오 ID를 입력해주세요.")
    private String kakaoId;

    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)")
    private String phone;

    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;

    private String profileImageUrl;
}
