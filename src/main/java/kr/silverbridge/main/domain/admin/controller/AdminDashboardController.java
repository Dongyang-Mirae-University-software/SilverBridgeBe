package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AdminOperationDashboardResponse;
import kr.silverbridge.main.domain.admin.dto.AdminSafetyDashboardResponse;
import kr.silverbridge.main.domain.admin.service.AdminDashboardService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 대시보드 집계 API.
 *
 * <p><b>탭마다 엔드포인트가 따로다</b> - 한쪽만 열어도 다른 쪽 쿼리가 돌지 않게 하기 위함이다.</p>
 *
 * <p>인가는 {@code SecurityConfig}의 {@code /api/admin/**} → {@code hasRole("ADMIN")} 경로 규칙이
 * 1차로 담당하고, 클래스 레벨 {@code @PreAuthorize}가 같은 조건을 한 번 더 건다. 경로 규칙만 두면
 * 그 보장이 컨트롤러 밖에 있어 테스트로 고정할 수 없고, 나중에 경로 패턴이 바뀌면 조용히 열린다.
 * <b>조회는 감사 로그에 남기지 않는다</b> - 집계 숫자만 반환해
 * 개인 식별 정보가 없고, 폴링으로 계속 호출되는 화면이라 기록하면 공지 수정 같은 실제 조작 이력이 묻힌다.
 * 개인 이력을 열람하는 관리자 API(이상감지 로그 등)는 그때 별도로 기록한다.</p>
 */
@Tag(name = "관리자 - 대시보드")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    @Operation(summary = "안전 현황 조회",
            description = """
                    안전망 지표를 집계합니다.

                    [AI가 끊겨 있어도 200]
                    aiConnected=false 로 내려가고 나머지 집계는 정상 반환됩니다.
                    이때 streamingCameras·disconnectedCameras 는 **null**(알 수 없음)입니다.
                    0대가 아니라 "우리 수신기가 끊겨 판단할 수 없다"는 뜻입니다.

                    [오늘 이상감지]
                    단위는 감지 건수가 아니라 **상황(incident)** 이며 KST 기준입니다.
                    byType 에는 실제로 집계된 유형만 담깁니다(0건 유형은 항목 자체가 없습니다).
                    review 의 네 값으로 오탐률을 **응답률과 함께** 계산하세요
                    (응답 수 = total - pending). 오탐 건수만 단독 표시하면 분모가 거짓이 됩니다.

                    [폴링]
                    30초 이상을 권장합니다. 서버 캐시는 두지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "안전 현황 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/safety")
    public ApiResponse<AdminSafetyDashboardResponse> getSafetyDashboard() {
        return ApiResponse.ok(adminDashboardService.getSafetyDashboard());
    }

    @Operation(summary = "운영 현황 조회",
            description = """
                    회원·문의·연결 등 운영 지표를 집계합니다.

                    [회원 수]
                    ADMIN 은 제외합니다(운영자는 서비스 이용자가 아닙니다).

                    [날짜]
                    "오늘"과 가입 추이는 모두 KST 기준입니다.
                    signupTrend 는 오늘 포함 최근 7일이며, 가입이 0건인 날도 항목이 있습니다.

                    [미답변 문의]
                    longestWaitingHours 가 null 이면 대기 중인 문의가 없다는 뜻입니다(0시간이 아닙니다).

                    [폴링]
                    30초 이상을 권장합니다. 서버 캐시는 두지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "운영 현황 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/operation")
    public ApiResponse<AdminOperationDashboardResponse> getOperationDashboard() {
        return ApiResponse.ok(adminDashboardService.getOperationDashboard());
    }
}
