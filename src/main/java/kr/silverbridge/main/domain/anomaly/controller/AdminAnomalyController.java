package kr.silverbridge.main.domain.anomaly.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.service.AdminAnomalyService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 이상감지 로그 API(보기 전용).
 *
 * <p>보호자 응답과 판정 결과를 조회만 한다. 인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨
 * {@code @PreAuthorize}가 이중으로 담당한다(관리자 대시보드와 같은 방식).</p>
 *
 * <p><b>관리자용 판정·정정 API를 여기에 추가하지 말 것</b>(2026-09-21 폐지). 판정은 현장을 아는 보호자의
 * 다수결로만 정해지고, 동수는 보호자들이 다시 응답해 푼다.</p>
 */
@Tag(name = "관리자 - 이상감지")
@RestController
@RequestMapping("/api/admin/anomaly")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnomalyController {

    private final AdminAnomalyService adminAnomalyService;

    @Operation(summary = "이상감지 기록 목록",
            description = """
                    이상감지 기록을 상황 단위로, 최신순으로 조회합니다.

                    [보호자 조회와 다른 점]
                    연결 여부로 좁히지 않고 **전체**를 봅니다. 조회 전용이며 관리자가 판정을 바꾸는 API는 없습니다.

                    [보호자 응답 내역이 함께 옵니다]
                    feedbacks 에 누가 무엇이라고 답했는지가 들어 있습니다.
                    아무도 답하지 않았으면 **빈 배열**입니다(null 아님).

                    [판정 상태]
                    응답한 보호자의 다수결입니다. status=CONFLICTED 는 응답이 **동수**로 갈린 건입니다
                    (보호자들에게 재확인 안내가 나가며, 다시 응답하면 풀립니다).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping
    public ApiResponse<PageResponse<AdminAnomalyIncidentItem>> getIncidents(
            @Parameter(description = "판정 상태 필터 (생략 시 전체)")
            @RequestParam(required = false) AnomalyReviewStatus status,

            @Parameter(description = "피보호자 ID 필터 (생략 시 전체)")
            @RequestParam(required = false) String wardId,

            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {

        return ApiResponse.ok(adminAnomalyService.getIncidents(status, wardId, page, size));
    }
}
