package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원관리 탭별 건수. 목록과 같은 모집단(탈퇴 진행 중인 계정 제외)을 센다.
 *
 * <p>여기서는 0건인 역할도 키를 남긴다 - 탭은 항상 네 개가 그려지고, "관리자 (0)"은
 * "관리자 계정이 없다"는 사실 그대로를 뜻하기 때문이다.</p>
 */
@Schema(description = "회원관리 탭별 건수")
public record AdminUserCountsResponse(

        @Schema(description = "전체", example = "20")
        long total,

        @Schema(description = "보호자", example = "7")
        long guardian,

        @Schema(description = "피보호자", example = "10")
        long ward,

        @Schema(description = "관리자", example = "3")
        long admin
) {}
