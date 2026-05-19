package kr.silverbridge.main.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Gender;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Builder
@Schema(description = "내 프로필 응답")
public class UserProfileResponse {

    @Schema(description = "사용자 ID", example = "aB3x9Z")
    private String id;

    @Schema(description = "이메일", example = "user@example.com")
    private String email;

    @Schema(description = "이름", example = "홍길동")
    private String name;

    @Schema(description = "전화번호 (없으면 null)", example = "01012345678", nullable = true)
    private String phone;

    @Schema(description = "가입 경로", allowableValues = {"LOCAL", "KAKAO"}, example = "LOCAL")
    private String provider;

    @Schema(description = "역할. WARD: 피보호자, GUARDIAN: 보호자, ADMIN: 관리자", allowableValues = {"WARD", "GUARDIAN", "ADMIN"}, example = "WARD")
    private String role;

    @Schema(description = "프로필 이미지 URL (없으면 null)", example = "https://file.silverbridge.kr/profiles/uuid.jpg", nullable = true)
    private String profileImage;

    @Schema(description = "성별. FEMALE: 여성, MALE: 남성. 기존 사용자 미입력 시 null → 프로필 수정에서 보완 입력 유도",
            allowableValues = {"FEMALE", "MALE"}, example = "FEMALE", nullable = true)
    private Gender gender;

    @Schema(description = "생년월일 (yyyy-MM-dd). 기존 사용자 미입력 시 null", example = "1990-03-15", nullable = true)
    private LocalDate birthDate;

    @Schema(description = "우편번호 (5자리). 기존 사용자 미입력 시 null", example = "06236", nullable = true)
    private String postcode;

    @Schema(description = "도로명 주소 또는 지번 주소", example = "서울특별시 강남구 테헤란로 123")
    private String address;

    @Schema(description = "상세 주소", example = "101동 202호")
    private String addressDetail;

    @Schema(description = "마지막 로그인 일시 (없으면 null)", example = "2025-01-01T09:00:00+09:00", nullable = true)
    private OffsetDateTime lastLoginAt;

    @Schema(description = "가입 일시", example = "2025-01-01T09:00:00+09:00")
    private OffsetDateTime createdAt;

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .phone(user.getPhone())
                .provider(user.getProvider().name())
                .role(user.getRole().name())
                .profileImage(user.getProfileImage())
                .gender(user.getGender())
                .birthDate(user.getBirthDate())
                .postcode(user.getPostcode())
                .address(user.getAddress())
                .addressDetail(user.getAddressDetail())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
