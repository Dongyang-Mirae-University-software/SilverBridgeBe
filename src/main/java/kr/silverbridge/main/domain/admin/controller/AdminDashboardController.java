package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.silverbridge.main.domain.admin.dto.AdminDashboardSummaryResponse;
import kr.silverbridge.main.domain.admin.dto.AdminPendingItemsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminRecentUserResponse;
import kr.silverbridge.main.domain.admin.service.AdminDashboardService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "관리자 - 대시보드")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Validated
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    @Operation(summary = "대시보드 통계 요약",
            description = """
                    관리자 대시보드 메인 카드에 표시되는 통계 수치를 반환합니다.

                    [포함 지표]
                    - 전체 활성 사용자 수 + 전월 대비 증감률(%)
                    - 활성 피보호자 수 + 전월 대비 증감률(%)
                    - 오늘 이상감지 건수 + 전일 대비 증감 수
                    - 전체 이상감지 누적 건수 + 오늘 신규 발생 수

                    [캐싱]
                    - Redis 60초 TTL. 같은 시간대 반복 호출은 캐시 응답.

                    [증감률 계산]
                    - baseline(전월 동일 시점) 값이 0 이면 증감률은 null 로 반환됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "통계 요약 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/summary")
    public ApiResponse<AdminDashboardSummaryResponse> getSummary() {
        return ApiResponse.ok(dashboardService.getSummary());
    }

    @Operation(summary = "최근 가입 회원 목록",
            description = """
                    최근에 가입한 회원 목록을 가입 일시 내림차순으로 반환합니다. ADMIN 계정은 제외됩니다.

                    [파라미터]
                    - limit: 가져올 인원수 (기본 5, 최대 50)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "limit 범위 위반", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/recent-users")
    public ApiResponse<List<AdminRecentUserResponse>> getRecentUsers(
            @Parameter(description = "가져올 인원수 (1~50)", example = "5")
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int limit) {
        return ApiResponse.ok(dashboardService.getRecentUsers(limit));
    }

    @Operation(summary = "처리 대기 현황",
            description = """
                    관리자가 처리해야 할 항목들의 건수를 반환합니다.

                    [포함 항목]
                    - 수락/거절되지 않은 피보호자 연결 요청 건수 (Connection.status = PENDING)
                    - 임시저장된 공지 건수
                    - 미확인 고객 문의 건수 (문의 기능 추가 전 — 현재는 항상 0)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 대기 현황 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/pending")
    public ApiResponse<AdminPendingItemsResponse> getPendingItems() {
        return ApiResponse.ok(dashboardService.getPendingItems());
    }
}