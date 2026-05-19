package kr.silverbridge.main.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
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
@Schema(description = "일반 회원가입 요청 (SMS 인증 완료 후 호출)")
public class RegisterRequest {

    @Schema(description = "이메일 주소", example = "user@example.com")
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @Schema(description = "비밀번호 (영문·숫자·특수문자 포함, 공백 없이 8자 이상)", example = "Password1!")
    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~])[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~]+$",
            message = "비밀번호는 영문·숫자·특수문자를 포함하고, 공백 없이 8글자 이상이어야 합니다."
    )
    private String password;

    @Schema(description = "이름 (최대 20자)", example = "홍길동")
    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
    private String name;

    @Schema(description = "전화번호 (숫자만, 하이픈 없이 10~11자리)", example = "01012345678")
    @NotBlank(message = "전화번호를 입력해주세요.")
    @Pattern(
            regexp = "^\\d{10,11}$",
            message = "전화번호는 숫자만 입력 가능합니다. (10~11자리)"
    )
    private String phone;

    @Schema(description = "SMS 인증 확인(POST /api/auth/signup/sms/verify) 응답에서 받은 verificationNonce 값을 그대로 전달",
            example = "550e8400-e29b-41d4-a716-446655440000")
    @NotBlank(message = "전화번호 인증 정보가 필요합니다. SMS 인증을 다시 진행해주세요.")
    private String verificationNonce;

    @Schema(description = "역할 선택. WARD: 피보호자, GUARDIAN: 보호자", example = "WARD", allowableValues = {"WARD", "GUARDIAN"})
    @NotNull(message = "역할을 선택해주세요. (WARD: 피보호자, GUARDIAN: 보호자)")
    private Role role;

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
