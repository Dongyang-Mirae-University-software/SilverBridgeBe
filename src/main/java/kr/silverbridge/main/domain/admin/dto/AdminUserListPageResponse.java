package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "관리자 회원 목록 (페이지)")
public record AdminUserListPageResponse(

        @Schema(description = "현재 페이지 항목 목록")
        List<UserSummaryResponse> content,

        @Schema(description = "현재 페이지 번호 (0-based)", example = "0")
        int page,

        @Schema(description = "한 페이지 크기", example = "10")
        int size,

        @Schema(description = "전체 건수", example = "237")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "24")
        int totalPages
) {

    public static AdminUserListPageResponse from(Page<User> page) {
        return new AdminUserListPageResponse(
                page.getContent().stream().map(UserSummaryResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
