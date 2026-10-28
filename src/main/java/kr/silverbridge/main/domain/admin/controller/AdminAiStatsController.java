package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AdminAiChatStatsResponse;
import kr.silverbridge.main.domain.admin.service.AdminAiChatStatsService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 AI 통계 API - 조회 전용(2026-10-28). 지금은 의료 챗봇 탭 하나다.
 *
 * <p>인가는 {@code SecurityConfig}의 {@code /api/admin/**} 경로 규칙과 클래스 레벨 {@code @PreAuthorize}가
 * 이중으로 담당한다. 조회라 감사 로그는 남기지 않는다. 쓰기 매핑을 더하려면 {@code AdminAiStatsControllerSecurityTest}의
 * 고정부터 정책과 함께 바꿀 것.</p>
 */
@Tag(name = "관리자 - AI 통계")
@RestController
@RequestMapping("/api/admin/ai-stats")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiStatsController {

    private final AdminAiChatStatsService adminAiChatStatsService;

    @Operation(summary = "AI 의료 챗봇 집계",
            description = """
                    기간 안 AI 의료 챗봇 상담의 개수만 돌려줍니다(상담 내용·사용자 ID 없음).

                    [기간]
                    - from·to: KST 날짜(YYYY-MM-DD), 둘 다 필수·둘 다 포함, 최대 31일. 어기면 400 AI_STATS_INVALID_RANGE

                    [값 읽는 법]
                    - emergencyGuidance: 응급 안내(119)를 낸 상담 수 - 고위험 판정 기준이라 실제 응급 수보다 조금 많을 수 있음
                    - byRole.unknown: 역할이 기록되지 않은 상담(2026-10-28 이전 일부 기록)
                    - 시험 계정 상담도 포함됩니다
                    - 개수가 null이면 AI가 셀 수 없는 값입니다(0으로 표시하지 마세요)
                    - generatedAt: AI가 집계한 시각. 같은 기간은 60초 동안 같은 결과를 돌려줍니다

                    화면에 들어오거나 기간을 바꿀 때만 부르세요(자동 반복 호출 금지). 관리자당 분 30회·시간 600회.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "집계 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "기간 형식·범위 오류(AI_STATS_INVALID_RANGE)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "요청이 너무 많음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI 서버에 연결할 수 없거나 응답 형식 이상(AI_STATS_UNAVAILABLE)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "504", description = "AI 응답 지연(AI_STATS_TIMEOUT)", content = @Content)
    })
    @GetMapping("/chat")
    public ApiResponse<AdminAiChatStatsResponse> getChatStats(
            @AuthenticationPrincipal String adminId,
            @Parameter(description = "시작일(KST, YYYY-MM-DD, 포함)", example = "2026-10-01")
            @RequestParam(required = false) String from,
            @Parameter(description = "종료일(KST, YYYY-MM-DD, 포함)", example = "2026-10-31")
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(adminAiChatStatsService.getChatStats(adminId, from, to));
    }
}
