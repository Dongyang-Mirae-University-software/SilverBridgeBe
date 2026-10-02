package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.global.response.PageResponse;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 보호자 SOS 이력 응답 - 페이지 + 경로별 전체 건수(SOS-G12).
 *
 * <p><b>하위호환</b>: 기존 응답({@link PageResponse})의 필드를 이름·위치 그대로 평평하게 두고 {@code counts}만
 * 더했다. 쓰지 않는 프론트는 영향받지 않는다.</p>
 *
 * <p>{@code totalElements}는 <b>요청한 필터(triggerType) 기준</b>, {@code counts}는 <b>필터와 무관한 전체 기준</b>이다.
 * 탭 숫자는 {@code counts}로 그려야 페이지를 넘겨도 바뀌지 않는다.</p>
 */
@Schema(description = "보호자 SOS 이력 페이지 + 경로별 건수")
public record SosHistoryPage(

        @Schema(description = "현재 페이지 항목")
        List<SosHistoryItem> content,

        @Schema(description = "현재 페이지 번호 (0-based)", example = "0")
        int page,

        @Schema(description = "페이지 크기", example = "20")
        int size,

        @Schema(description = "필터(triggerType) 기준 전체 항목 수", example = "137")
        long totalElements,

        @Schema(description = "필터(triggerType) 기준 전체 페이지 수", example = "7")
        int totalPages,

        @Schema(description = "마지막 페이지 여부", example = "false")
        boolean last,

        @Schema(description = "경로별 전체 건수 (필터·페이지와 무관, 조회 범위의 피보호자 기준)")
        Counts counts
) {

    /**
     * 경로별 전체 건수. {@code all = sosButton + guardianCall}.
     */
    @Schema(description = "경로별 SOS 이력 건수")
    public record Counts(

            @Schema(description = "전체", example = "60")
            long all,

            @Schema(description = "긴급 SOS 버튼(SOS_BUTTON)", example = "55")
            long sosButton,

            @Schema(description = "보호자에게 직접 전화(GUARDIAN_CALL)", example = "5")
            long guardianCall
    ) {
        public static final Counts EMPTY = new Counts(0, 0, 0);

        public static Counts of(long sosButton, long guardianCall) {
            return new Counts(sosButton + guardianCall, sosButton, guardianCall);
        }
    }

    public static SosHistoryPage of(Page<SosHistoryItem> page, Counts counts) {
        return new SosHistoryPage(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast(),
                counts
        );
    }
}
