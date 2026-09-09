package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;

import java.time.OffsetDateTime;
import java.util.List;

/** 회원 상세. 수정 모달이 쓰는 값과 연결 전체 목록을 담는다. */
@Schema(description = "회원 상세")
public record AdminUserDetailResponse(

        @Schema(description = "회원 ID (6자리)", example = "EE81BF")
        String userId,

        @Schema(description = "가입 방식", example = "KAKAO", allowableValues = {"LOCAL", "KAKAO"})
        Provider provider,

        @Schema(description = "이메일 (관리자가 수정할 수 없다)", example = "hong@example.com")
        String email,

        @Schema(description = "이름", example = "홍길동")
        String name,

        @Schema(description = "전화번호 (관리자가 수정할 수 없다)", example = "010-1234-5678")
        String phone,

        @Schema(description = "역할", example = "GUARDIAN", allowableValues = {"WARD", "GUARDIAN", "ADMIN"})
        Role role,

        @Schema(description = "계정 상태 (ACTIVE=이용 중, RESTRICTED=이용 제한)",
                example = "ACTIVE", allowableValues = {"ACTIVE", "RESTRICTED"})
        Status status,

        @Schema(description = "연결 상태. 관리자 계정은 null", nullable = true, example = "CONNECTED")
        AdminUserConnectionState connectionState,

        @Schema(description = "연결(ACTIVE+PENDING) 전체 목록. 없으면 빈 배열")
        List<AdminUserConnectionItem> connections,

        @Schema(description = "가입 일시")
        OffsetDateTime createdAt,

        @Schema(description = "마지막 로그인 일시 (로그인 이력이 없으면 null)", nullable = true)
        OffsetDateTime lastLoginAt
) {
    public static AdminUserDetailResponse of(User user, List<AdminUserConnectionItem> connections) {
        boolean hasConnectionAxis = user.getRole() != Role.ADMIN;
        return new AdminUserDetailResponse(
                user.getId(),
                user.getProvider(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                hasConnectionAxis ? AdminUserConnectionStates.of(connections) : null,
                connections,
                user.getCreatedAt(),
                user.getLastLoginAt()
        );
    }
}
