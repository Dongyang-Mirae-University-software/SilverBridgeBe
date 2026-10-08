package kr.silverbridge.main.domain.notification.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingRequest;
import kr.silverbridge.main.domain.notification.dto.GuardianNotificationTypeSettingResponse;
import kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 보호자 환경설정 "알림 종류" 탭. 클래스 레벨 GUARDIAN 전용(WARD/ADMIN 403), 보호자 ID는 토큰에서만 받는다. */
@Tag(name = "보호자 - 알림 종류 설정")
@RestController
@RequestMapping("/api/guardian/notification-type-setting")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianNotificationTypeSettingController {

    private final GuardianNotificationPreferenceService service;

    @Operation(summary = "알림 종류별 수신 설정 조회 (보호자 전용)",
            description = """
                    [응답 data] 종류별 { enabled, required }
                      - sos               : 필수(required=true), 항상 켜짐. 변경 불가
                      - anomalyDetection  : 필수(required=true), 항상 켜짐. 변경 불가.
                                            이상감지 발생 알림의 푸시는 어떤 설정으로도 꺼지지 않습니다.
                      - anomalyReviewReminder : 판정 확인 요청(재촉) 알림. 기본 true
                      - medication        : 복약 미복용 요약 알림. 기본 true
                    required=true 항목은 FE가 잠금 표시만 하면 됩니다.

                    [참고]
                    - 알림을 받는 방법(푸시·문자·알림톡·이메일)은 /api/user/me/notification-settings 입니다.
                      이메일은 설정값만 저장되고 발송은 아직 구현되지 않았습니다.
                    - 정서 변화·병원 예약 알림은 BE가 보내지 않아 설정 대상이 아닙니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "현재 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping
    public ApiResponse<GuardianNotificationTypeSettingResponse> getSettings(@AuthenticationPrincipal String guardianId) {
        return ApiResponse.ok(service.getSettings(guardianId));
    }

    @Operation(summary = "알림 종류별 수신 설정 변경 (보호자 전용)",
            description = """
                    변경 가능한 종류(anomalyReviewReminder, medication)만 받습니다. null/생략은 변경하지 않습니다.
                    sos·anomalyDetection 등 필수 알림이나 알 수 없는 키를 보내도 무시되며 저장되지 않습니다.

                    [복약] 끄면 미복용 요약이 오지 않습니다. 피보호자별 미복용 요약 설정
                    (/api/guardian/ward/{wardId}/medication-alert-setting)과 둘 다 켜져 있어야 발송됩니다.
                    등록 보호자 탈퇴로 약이 중지됐다는 안내는 이 설정과 무관하게 갑니다.
                    [재촉] 기존 /api/guardian/anomaly/reminder-setting 과 같은 값입니다.
                    응답으로 변경 후 전체 설정을 반환합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경된 설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @PutMapping
    public ApiResponse<GuardianNotificationTypeSettingResponse> updateSettings(
            @AuthenticationPrincipal String guardianId,
            @RequestBody GuardianNotificationTypeSettingRequest request) {
        return ApiResponse.ok(service.updateSettings(guardianId, request));
    }
}
