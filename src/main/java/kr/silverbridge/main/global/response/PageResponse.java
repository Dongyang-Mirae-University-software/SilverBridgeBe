package kr.silverbridge.main.global.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 페이징 목록 공통 응답. Spring Data {@link Page}를 클라이언트 친화적인 평평한 형태로 감싼다.
 *
 * <p>{@code ApiResponse<PageResponse<T>>} 형태로 사용한다. 코드베이스 첫 페이징 사용처(문의 관리자 목록)에서
 * 도입했으며, 이후 페이징이 필요한 다른 목록에서도 재사용한다.</p>
 */
@Schema(description = "페이징 목록 응답")
public record PageResponse<T>(

        @Schema(description = "현재 페이지 항목")
        List<T> content,

        @Schema(description = "현재 페이지 번호 (0-based)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "전체 항목 수", example = "137")
        long totalElements,

        @Schema(description = "전체 페이지 수", example = "7")
        int totalPages,

        @Schema(description = "마지막 페이지 여부", example = "false")
        boolean last
) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}
