package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AdminAiStatusResponse;
import kr.silverbridge.main.domain.admin.dto.AdminOperationDashboardResponse;
import kr.silverbridge.main.domain.admin.dto.AdminSafetyDashboardResponse;
import kr.silverbridge.main.domain.admin.service.AdminAiStatusService;
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
    private final AdminAiStatusService adminAiStatusService;

    @Operation(summary = "안전 현황 조회",
            description = """
                    안전망 지표를 집계합니다.

                    [AI가 끊겨 있어도 200]
                    aiConnected=false 로 내려가고 나머지 집계는 정상 반환됩니다.
                    이때 streamingCameras·disconnectedCameras 는 **null**(알 수 없음)입니다.
                    AI와 다시 연결된 직후 첫 세션 목록을 받기 전(수 초)에도 aiConnected=true 인 채로 두 값이 null 입니다.
                    0대가 아니라 "우리 수신기가 끊겨 판단할 수 없다"는 뜻입니다.

                    [오늘 이상감지]
                    단위는 감지 건수가 아니라 **상황(incident)** 이며 KST 기준입니다.
                    byType 에는 실제로 집계된 유형만 담깁니다(0건 유형은 항목 자체가 없습니다).
                    review 의 네 값으로 오탐률을 **응답률과 함께** 계산하세요
                    (응답 수 = total - pending). 오탐 건수만 단독 표시하면 분모가 거짓이 됩니다.
                    aiConfidence 는 판정 난 상황(위험+오탐)만의 AI confidence 평균이며, 판정 난 상황이 0건이면 average 가 null 입니다.

                    [전달 실패한 알림 - safetyEvents.todayFailedNotifications]
                    오늘(KST) 전달 실패(FAILED) 건수로, 알림 이력 요약 period=TODAY 의 failed 와 같습니다.
                    발송 안 함(NOT_SENT - 정지 차단·채널 꺼 둠)은 실패가 아니라 세지 않습니다.

                    [연결이 끊긴 카메라]
                    "지금" 끊긴 대수입니다. 끊긴 시각을 저장하지 않아 "24시간 넘게 꺼진 카메라"는 알 수 없습니다.

                    [generatedAt]
                    응답을 만든 시각(KST, 오프셋 포함)입니다. "N분 전 확인" 표시에 쓰세요.

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

                    [카메라 - 단위가 다릅니다]
                    registered(등록 대수) · wards(카메라를 등록한 피보호자 수, 명) · maxTotal(서비스 등록 상한, 대 - 꺼져 있으면 null).
                    "등록 대수 / 상한"은 registered / maxTotal 입니다. 대수와 사람 수를 한 분수로 섞지 마세요.

                    [generatedAt]
                    응답을 만든 시각(KST, 오프셋 포함)입니다.

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

    @Operation(summary = "AI 서비스 상태 조회",
            description = """
                    BE가 확인할 수 있는 AI 서비스의 상태를 요청 때마다 새로 확인합니다(서버 캐시 없음).

                    [anomalyDetection - 이상감지(영상 분석)]
                    백엔드의 AI WebSocket 구독 상태입니다. OK(연결됨) / DISCONNECTED(끊김 - 이상감지 알림이 나가지 않음)
                    / null(확인 중 - 재접속 직후 첫 세션 목록 전 수 초).

                    [chatbot - AI 의료 챗봇]
                    챗봇을 서빙하는 AI 서버의 응답입니다(키가 필요한 조회 GET /api/v1/live-streams로 확인, 연결 3초·전체 5초 제한).
                    AI /health 는 앞단이 바깥에 열지 않아 쓰지 않습니다.
                    OK(정상 응답) / DISABLED(운영자가 챗봇 전송을 꺼 둠 - AI를 부르지 않음)
                    / null(확인 불가 - 시간 초과·통신 오류·서버 오류·설정 없음).
                    AI 서버 프로세스 응답까지만 확인하며 모델 응답 성공은 보장하지 않습니다.
                    확인 실패를 "중단"으로 단정하지 않으므로 이 칸에는 DISCONNECTED가 오지 않습니다.

                    [만들지 않은 항목]
                    "AI 안부 말벗 · 정서 상태 분석"은 BE에 없는 서비스라 상태를 내리지 않습니다.

                    [응답 시간]
                    AI가 응답하지 않으면 최대 약 5초 걸립니다. 안전 현황(/safety)과 따로 호출하세요.
                    동시에 들어온 요청은 진행 중인 확인 하나를 함께 기다립니다(결과를 저장해 두지는 않음).
                    폴링은 60초 이상을 권장합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "AI 서비스 상태 반환(확인 실패도 200 + null)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/ai-status")
    public ApiResponse<AdminAiStatusResponse> getAiStatus() {
        return ApiResponse.ok(adminAiStatusService.getAiStatus());
    }
}
