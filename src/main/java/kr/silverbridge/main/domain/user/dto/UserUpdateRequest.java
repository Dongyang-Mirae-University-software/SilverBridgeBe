package kr.silverbridge.main.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;

@Getter
@Schema(description = "내 정보 수정 요청. 전화번호를 변경하는 경우 반드시 SMS 인증 완료 후 호출해야 합니다.")
public class UserUpdateRequest {

    @Schema(description = "변경할 이름", example = "홍길동")
    @NotBlank(message = "이름은 필수입니다.")
    @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
    private String name;

    @Schema(description = "전화번호 (숫자만, 하이픈 없이 10~11자리). 변경 시 새 번호로 SMS 인증(POST /api/auth/sms/verify) 완료 후 호출해야 합니다.", example = "01098765432")
    @NotBlank(message = "전화번호는 필수입니다.")
    @Pattern(regexp = "^\\d{10,11}$", message = "전화번호는 숫자 10~11자리여야 합니다.")
    private String phone;

    @Schema(description = "SMS 인증 확인 응답의 verificationNonce. 전화번호를 변경하지 않는 경우 생략 가능. 변경 시 SMS 인증을 다시 진행해 받은 값을 전달.",
            example = "550e8400-e29b-41d4-a716-446655440000",
            nullable = true)
    private String verificationNonce;

    @Schema(description = "도로명 주소 또는 지번 주소 (카카오 주소 API 결과값)", example = "서울특별시 강남구 테헤란로 123")
    @NotBlank(message = "주소는 필수입니다.")
    @Size(max = 200, message = "주소는 200자 이하여야 합니다.")
    private String address;

    @Schema(description = "상세 주소 (동/호수 등)", example = "101동 202호")
    @NotBlank(message = "상세 주소는 필수입니다.")
    @Size(max = 100, message = "상세 주소는 100자 이하여야 합니다.")
    private String addressDetail;
}
