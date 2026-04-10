package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import kr.silverbridge.main.global.enums.Role;
import lombok.Getter;

@Getter
@Schema(description = "카카오 신규 회원가입 완료 요청 (SMS 인증 완료 후 호출)")
public class KakaoRegisterRequest {

    @Schema(description = "POST /api/auth/kakao 응답에서 받은 kakaoId 값을 그대로 전달", example = "3456789012")
    @NotBlank(message = "카카오 ID를 입력해주세요.")
    private String kakaoId;

    @Schema(description = "사용자 이름 (카카오 닉네임 또는 직접 입력)", example = "홍길동")
    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    @Schema(description = "SMS 인증을 완료한 전화번호 (숫자만, 하이픈 없이 10~11자리)", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)")
    private String phone;

    @Schema(description = "역할 선택. WARD: 피보호자, GUARDIAN: 보호자", example = "WARD", allowableValues = {"WARD", "GUARDIAN"})
    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;

    @Schema(description = "프로필 이미지 URL (선택값. 카카오 응답의 profileImageUrl 전달 또는 null)", example = "https://k.kakaocdn.net/dn/...", nullable = true)
    private String profileImageUrl;
}
