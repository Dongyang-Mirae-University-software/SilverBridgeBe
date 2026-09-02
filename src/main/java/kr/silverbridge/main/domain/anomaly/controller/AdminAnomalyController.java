package kr.silverbridge.main.domain.anomaly.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.dto.AdminAnomalyReviewRequest;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyReviewStatus;
import kr.silverbridge.main.domain.anomaly.service.AdminAnomalyService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 이상감지 로그 + 판정 정정 API.
 *
 * <p>보호자가 답한 결과가 엇갈린 건(<b>CONFLICTED</b>)을 관리자가 확인해 확정하는 자리다.
 * 인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨
 * {@code @PreAuthorize}가 이중으로 담당한다(관리자 대시보드와 같은 방식).</p>
 *
 * <p><b>관리자용 1차 판정 API를 여기에 추가하지 말 것.</b> 판정은 현장을 아는 보호자가 하고,
 * 관리자는 엇갈린 것을 정리하는 2차 역할만 맡는다.</p>
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
                    연결 여부로 좁히지 않고 **전체**를 봅니다. 관리자는 전체를 봐야 엇갈린 판정을 찾을 수 있습니다.

                    [보호자 응답 내역이 함께 옵니다]
                    feedbacks 에 누가 무엇이라고 답했는지가 들어 있습니다.
                    아무도 답하지 않았으면 **빈 배열**입니다(null 아님).

                    [정정 대상 찾기]
                    status=CONFLICTED 로 거르면 보호자끼리 답이 갈린 건만 나옵니다.
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

    @Operation(summary = "이상감지 판정 정정",
            description = """
                    보호자 응답이 엇갈렸거나 잘못 판정된 건을 관리자가 확정합니다.

                    [지정할 수 있는 값]
                    REAL(실제 위험) 또는 FALSE_ALARM(오탐) **둘뿐**입니다.
                    PENDING·CONFLICTED 로 되돌리면 400입니다 - 확인을 마친 뒤에 "아직 아무도 답하지 않음"으로
                    되돌리면 그 상태가 무엇을 뜻하는지 알 수 없게 됩니다. 판단이 서지 않으면 그냥 두면 됩니다.

                    [보호자 응답은 지워지지 않습니다]
                    상태만 바뀌고 누가 무엇이라고 답했는지는 그대로 남습니다.

                    [정정한 뒤에는]
                    - 보호자가 나중에 답해도 이 결정이 뒤집히지 않습니다(보호자 응답은 409로 막힙니다).
                    - 이미 정정한 건을 **다시 정정하는 것은 됩니다**. 관리자도 잘못 누를 수 있어서입니다.
                      정정할 때마다 감사 로그가 남습니다.
                    - **정정 알림은 보내지 않습니다.** "아까 그건 아니었습니다"를 다시 푸시하면
                      알림만 두 배가 되고 다음 진짜 경보의 신뢰가 깎입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "정정 후 항목 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "REAL·FALSE_ALARM 외의 값 지정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 상황", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PatchMapping("/{incidentId}/review")
    public ApiResponse<AdminAnomalyIncidentItem> resolve(
            @AuthenticationPrincipal String adminId,
            @Parameter(description = "상황 ID") @PathVariable Long incidentId,
            @Valid @RequestBody AdminAnomalyReviewRequest request) {

        return ApiResponse.ok(adminAnomalyService.resolve(
                adminId, incidentId, request.reviewStatus(), request.note()));
    }
}
