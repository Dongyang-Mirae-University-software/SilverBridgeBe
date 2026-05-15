package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 회원관리 탭별 건수")
public record AdminUserCountsResponse(

        @Schema(description = "전체 사용자 수 (ADMIN 포함)", example = "237")
        long total,

        @Schema(description = "피보호자 수", example = "118")
        long ward,

        @Schema(description = "보호자 수", example = "117")
        long guardian,

        @Schema(description = "관리자 수", example = "2")
        long admin
) {
}