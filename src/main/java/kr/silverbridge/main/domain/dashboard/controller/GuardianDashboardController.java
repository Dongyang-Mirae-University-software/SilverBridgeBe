package kr.silverbridge.main.domain.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.dashboard.dto.GuardianDashboardResponse;
import kr.silverbridge.main.domain.dashboard.service.GuardianDashboardService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호자 대시보드 통합 API. GUARDIAN 전용(WARD/ADMIN 403). 보호자 ID는 토큰에서만 받는다 - guardianId 파라미터는 없다.
 */
@Tag(name = "보호자 - 대시보드")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianDashboardController {

    private final GuardianDashboardService dashboardService;

    @Operation(summary = "보호자 대시보드 통합 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    대시보드 진입 시 이상감지·SOS·복약을 한 번에 받습니다. 정서·활동(게임)·병원 예약은 이 응답에 없으며
                    기존 경로를 그대로 쓰세요.

                    [wardId 파라미터]
                    - 지정: 해당 피보호자만 (ACTIVE 연결이 아니면 403)
                    - 생략: ACTIVE 연결된 피보호자 전원 합산 (연결이 없으면 wards는 빈 배열이고 건수는 모두 0, latest/mostUrgent는 null - 조회 실패가 아니라 실제로 센 0입니다)
                    - 보호자 ID는 토큰에서만 읽습니다. guardianId 파라미터는 없습니다.

                    [pendingActions - "확인이 필요한 일 N건"]
                    total = anomaly + medication
                    - anomaly: 이상감지 확인 필요(PENDING + CONFLICTED) 상황 수
                    - medication: 오늘(KST) 복용 시각이 지났는데 체크되지 않은 약 개수
                    - SOS는 보호자가 처리할 일이 없어 세지 않습니다
                    - 한 칸이라도 조회에 실패하면 total은 null입니다(합계를 추정하지 않음)

                    [wards] 피보호자 칩. pendingCount는 그 피보호자의 이상감지 + 복약 확인 필요 건수(칸 실패 시 null)

                    [anomalyDetection]
                    - needsReviewCount: 확인 필요 상황 수
                    - latest: 가장 최근 확인 필요 상황 1건(없으면 null). 항목 형식은 GET /api/guardian/anomaly/history 와 같고
                      clip/clipCount로 영상 유무를 알 수 있습니다. 영상 재생은 기존 클립 API를 쓰세요

                    [sos]
                    - thisMonthCount: 이번 달(KST 1일 00:00부터) 건수
                    - latest: 가장 최근 SOS 1건(이번 달이 아니어도 포함, 없으면 null)
                    - 'SOS 해결됨' 상태는 서버에 없습니다(처리 결과 기능은 2026-08-26 철회). 화면에 해결 여부를 표시하지 마세요

                    [medication]
                    - uncheckedCount / mostUrgent: 복용 시각이 지났는데 체크되지 않은 약과 그중 복용 시각이 가장 이른 1건
                    - 체크 누락과 실제 미복용을 서버는 구분하지 못합니다. 문구는 '체크되지 않았습니다'로 쓰고 '안 드셨습니다'로 단정하지 마세요

                    [칸 실패]
                    한 칸이 실패해도 전체가 500이 되지 않습니다. 실패한 칸은 0이 아니라 null이고, 칸 이름이 unavailable에 담깁니다(칸 null = 조회 실패. 연결 없음과 구분됩니다).
                    null을 0건으로 표시하지 마세요(재시도 또는 '확인 중' 표시).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "보호자가 아니거나, wardId가 ACTIVE 연결된 피보호자가 아님", content = @Content)
    })
    @GetMapping("/api/guardian/dashboard")
    public ResponseEntity<ApiResponse<GuardianDashboardResponse>> getDashboard(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "특정 피보호자만 볼 때 지정. 생략하면 ACTIVE 연결된 피보호자 전원")
            @RequestParam(required = false) String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getDashboard(guardianId, wardId)));
    }
}
