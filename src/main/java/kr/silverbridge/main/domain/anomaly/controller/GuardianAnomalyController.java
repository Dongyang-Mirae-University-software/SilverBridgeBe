package kr.silverbridge.main.domain.anomaly.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyFeedbackRequest;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyFeedbackResponse;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyIncidentItem;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyReminderSettingRequest;
import kr.silverbridge.main.domain.anomaly.dto.AnomalyReminderSettingResponse;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalySettingService;
import kr.silverbridge.main.domain.anomaly.service.GuardianAnomalyService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 보호자용 이상감지 이력 조회 + 오탐 응답 API.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 *
 * <p>판정 주체는 보호자뿐이다 - 피보호자 본인·관리자용 1차 판정 엔드포인트를 여기에 추가하지 말 것.</p>
 */
@Tag(name = "보호자 - 이상감지")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianAnomalyController {

    private final GuardianAnomalyService guardianAnomalyService;
    private final GuardianAnomalySettingService guardianAnomalySettingService;

    @Operation(summary = "피보호자 이상감지 이력 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자의 이상감지 이력을 최신순으로 반환합니다.

                    [단위는 "상황"입니다]
                    같은 카메라에서 화재가 이어지면 감지 이력은 1분 간격으로 여러 건 쌓이지만,
                    10분 이내의 연속 감지는 하나의 상황으로 묶여 한 건으로 나옵니다(eventCount = 묶인 횟수).
                    상황은 KST 자정을 넘기지 않습니다.

                    [wardId 파라미터]
                    - 지정: 해당 피보호자의 이력만 (연결이 ACTIVE가 아니면 403)
                    - 생략: ACTIVE 연결된 피보호자 전원의 이력을 합쳐서 최신순 (연결이 없으면 빈 페이지)

                    [응답] data: PageResponse<AnomalyIncidentItem>
                    - reviewStatus: PENDING(확인 필요) · REAL(실제 위험) · FALSE_ALARM(오탐) · CONFLICTED(보호자 응답 엇갈림)
                    - myVerdict: 내가 낸 응답. 아직 응답하지 않았으면 null
                    - resolvedByAdmin: true면 관리자가 확정한 건이라 응답을 바꿀 수 없습니다(응답 버튼 비활성화 권장)
                    - cameraLabel: 카메라가 삭제되면 null입니다(이력 자체는 남습니다)

                    [주의]
                    - 연결이 해제되면 그 피보호자의 과거 이력도 조회되지 않습니다.
                    - size는 최대 50으로 제한됩니다(초과 요청은 50으로 처리).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이상감지 이력 페이지 반환 (연결된 피보호자가 없으면 빈 content)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자 지정", content = @Content)
    })
    @GetMapping("/api/guardian/anomaly/history")
    public ResponseEntity<ApiResponse<PageResponse<AnomalyIncidentItem>>> getHistory(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "특정 피보호자만 조회 (생략 시 연결된 피보호자 전원)") @RequestParam(required = false) String wardId,
            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                guardianAnomalyService.getHistory(guardianId, wardId, page, size)));
    }

    @Operation(summary = "이상감지 오탐 응답 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    감지가 실제 위험이었는지(REAL) 오탐이었는지(FALSE_ALARM)를 남깁니다.
                    AI는 "얼마나 불꽃처럼 보이는가"까지만 답할 수 있어, 실제 여부는 현장을 아는 보호자만 알 수 있습니다.

                    [1인 1표 · 번복 가능]
                    같은 상황에 다시 호출하면 이전 응답을 덮어씁니다(새 응답이 쌓이지 않습니다).

                    [상태 재계산]
                    응답할 때마다 그 상황의 응답 전체를 다시 집계합니다.
                    - 응답한 보호자 전원이 같은 답 → REAL 또는 FALSE_ALARM
                    - 답이 갈리면 → CONFLICTED (다수결로 정하지 않습니다. 관리자가 확인합니다)
                    응답 결과로 CONFLICTED가 돌아와도 내 응답이 거부된 것이 아닙니다.

                    [주의]
                    - 이 응답은 이미 나간 알림을 되돌리지 않습니다(정정 알림을 발송하지 않습니다).
                    - 관리자가 확정한 건(resolvedByAdmin=true)은 409입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "응답 저장 + 재계산된 판정 상태 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "판단(verdict) 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자의 기록", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "이상감지 기록 없음", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "관리자가 확정한 기록이라 응답 변경 불가", content = @Content)
    })
    @PostMapping("/api/guardian/anomaly/{incidentId}/feedback")
    public ResponseEntity<ApiResponse<AnomalyFeedbackResponse>> submitFeedback(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "상황 ID") @PathVariable Long incidentId,
            @Valid @RequestBody AnomalyFeedbackRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                guardianAnomalyService.submitFeedback(guardianId, incidentId, request.verdict())));
    }
    @Operation(summary = "판정 재촉 알림 수신 설정 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    응답하지 않은 이상감지에 대해 확인 요청 알림을 받을지 여부입니다.
                    한 번도 설정하지 않았으면 기본값 true가 반환됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "현재 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/anomaly/reminder-setting")
    public ResponseEntity<ApiResponse<AnomalyReminderSettingResponse>> getReminderSetting(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(guardianAnomalySettingService.getSetting(guardianId)));
    }

    @Operation(summary = "판정 재촉 알림 수신 설정 변경 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [재촉이란]
                    이상감지 상황이 끝난 뒤 1시간이 지나도 아무도 응답하지 않으면 확인 요청 알림이 한 번 가고,
                    그 뒤로는 하루 한 번 미응답 건수를 요약해 알립니다. 3일이 지나면 더 보내지 않습니다.
                    밤(22:00~08:00)에는 보내지 않고 다음 아침으로 미룹니다. 채널은 앱 푸시뿐입니다.

                    [끄면]
                    확인 요청만 오지 않습니다. **이상감지 발생 알림 자체는 그대로 발송됩니다.**

                    [부분 수정]
                    null인 필드는 변경하지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경된 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @PutMapping("/api/guardian/anomaly/reminder-setting")
    public ResponseEntity<ApiResponse<AnomalyReminderSettingResponse>> updateReminderSetting(
            @AuthenticationPrincipal String guardianId,
            @RequestBody AnomalyReminderSettingRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                guardianAnomalySettingService.updateSetting(guardianId, request.reviewReminderEnabled())));
    }
}
