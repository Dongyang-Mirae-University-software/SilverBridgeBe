package kr.silverbridge.main.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.global.enums.Gender;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.validation.ValidBirthDate;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Schema(description = "카카오 신규 회원가입 완료 요청 (SMS 인증 완료 후 호출)")
public class KakaoRegisterRequest {

    @Schema(description = "POST /api/auth/kakao 응답에서 받은 kakaoId 값을 그대로 전달 (최대 20자)", example = "3456789012")
    @NotBlank(message = "카카오 ID를 입력해주세요.")
    @Size(max = 20, message = "카카오 ID 입력값이 올바르지 않습니다.")
    private String kakaoId;

    @Schema(description = "본인 실명 (카카오 닉네임 사용 불가, 사용자가 직접 입력, 최대 20자)", example = "홍길동")
    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
    private String name;

    @Schema(description = "SMS 인증을 완료한 전화번호 (숫자만, 하이픈 없이 10~11자리)", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)")
    private String phone;

    @Schema(description = "SMS 인증 확인(POST /api/auth/signup/sms/verify) 응답에서 받은 verificationNonce 값을 그대로 전달 (UUID 36자)",
            example = "550e8400-e29b-41d4-a716-446655440000")
    @NotBlank(message = "전화번호 인증 정보가 필요합니다. SMS 인증을 다시 진행해주세요.")
    @Size(max = 36, message = "전화번호 인증 정보가 올바르지 않습니다.")
    private String verificationNonce;

    @Schema(description = "역할 선택. WARD: 피보호자, GUARDIAN: 보호자", example = "WARD", allowableValues = {"WARD", "GUARDIAN"})
    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;

    @Schema(description = "프로필 이미지 URL (선택값. 카카오 응답의 profileImageUrl 전달 또는 null)", example = "https://k.kakaocdn.net/dn/...", nullable = true)
    private String profileImageUrl;

    @Schema(description = "도로명 주소 또는 지번 주소 (카카오 주소 API 결과값)", example = "서울특별시 강남구 테헤란로 123")
    @NotBlank(message = "주소를 입력해주세요.")
    @Size(max = 200, message = "주소는 200자 이하여야 합니다.")
    private String address;

    @Schema(description = "상세 주소 (동/호수 등)", example = "101동 202호")
    @NotBlank(message = "상세 주소를 입력해주세요.")
    @Size(max = 100, message = "상세 주소는 100자 이하여야 합니다.")
    private String addressDetail;

    @Schema(description = "성별. FEMALE: 여성, MALE: 남성", example = "FEMALE", allowableValues = {"FEMALE", "MALE"})
    @NotNull(message = "성별을 선택해주세요. (FEMALE: 여성, MALE: 남성)")
    private Gender gender;

    @Schema(description = "생년월일 (yyyy-MM-dd). 미래 날짜 불가, 만 14세 이상만 가입 가능", example = "1990-03-15", format = "date")
    @NotNull(message = "생년월일을 입력해주세요.")
    @ValidBirthDate
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate birthDate;

    @Schema(description = "우편번호 (카카오 주소 검색 결과의 5자리 zonecode)", example = "06236")
    @NotBlank(message = "우편번호를 입력해주세요. (주소 검색을 이용해주세요)")
    @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리여야 합니다.")
    private String postcode;
}
