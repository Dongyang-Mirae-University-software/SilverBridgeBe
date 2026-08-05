package kr.silverbridge.main.domain.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.medication.dto.GuardianMedicationAlertSettingRequest;
import kr.silverbridge.main.domain.medication.dto.GuardianMedicationAlertSettingResponse;
import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.MedicationSettingResponse;
import kr.silverbridge.main.domain.medication.dto.MedicationSettingUpdateRequest;
import kr.silverbridge.main.domain.medication.dto.WardMedicationSummary;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationService;
import kr.silverbridge.main.domain.medication.service.GuardianMedicationSettingService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 보호자용 복약 관리 API.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 *
 * <p><b>복용 체크 API는 여기 없다</b> — 체크·해제는 피보호자 전용({@code /api/ward/medication/...})이다.
 * 보호자 화면의 체크 표시는 읽기 전용이며, 이 구분이 "피보호자가 체크해야 보호자에게 보인다"는
 * 요구를 엔드포인트 구조로 보장한다.</p>
 */
@Tag(name = "보호자 - 복약 알림")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianMedicationController {

    private final GuardianMedicationService guardianMedicationService;
    private final GuardianMedicationSettingService guardianMedicationSettingService;

    @Operation(summary = "피보호자별 오늘 복약 현황 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자 전원의 오늘 복약 일정과 복용 현황을 한 번에 반환합니다.
                    (화면의 피보호자 카드 목록에 그대로 대응합니다.)

                    [응답] data: WardMedicationSummary[]
                    - wardId / wardName / age(만 나이, 생년월일 미등록 시 null)
                    - alarmEnabled: 복약 알림 ON/OFF (설정한 적 없으면 true)
                    - doseDate: 조회 기준일(KST)
                    - takenCount / totalCount: "오늘 2/3회 복용"의 두 숫자
                    - medications[]: 복용 시각 순. taken(체크 여부) / takenAt(체크 시각) 포함

                    [주의]
                    - 연결된 피보호자가 없으면 빈 배열입니다.
                    - 약이 하나도 없는 피보호자도 카드는 나옵니다(medications 빈 배열, 0/0).
                    - 연결이 해제되면 그 피보호자의 복약 정보는 즉시 조회되지 않습니다.
                    - 화면 문구("아침 08:00 · 1정 · 식후 30분")는 프론트가 조립합니다 — 서버는 원자값만 줍니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "복약 현황 반환 (연결된 피보호자가 없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/medication")
    public ResponseEntity<ApiResponse<List<WardMedicationSummary>>> getWardMedications(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(guardianMedicationService.getWardMedications(guardianId)));
    }

    @Operation(summary = "피보호자에게 약 추가 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자에게 복약 일정을 등록합니다.
                    약 등록은 보호자만 할 수 있습니다 — 피보호자에게는 등록 API가 없습니다.

                    [요청 바디]
                    - name (필수, 100자 이내): 약 이름 (예 "혈압약 (암로디핀 5mg)")
                    - timeSlot (필수): MORNING(아침) · LUNCH(점심) · DINNER(저녁) · BEDTIME(취침 전)
                    - doseTime (선택): 복용 시각. 생략 시 시간대 기본값
                      (MORNING 08:00 / LUNCH 13:00 / DINNER 18:00 / BEDTIME 22:00)
                    - doseAmount (선택, 1~99): 복용량(정). 생략 시 1
                    - memo (선택, 100자 이내): 복용 안내 (예 "식사와 함께")

                    [응답] data: MedicationItem — 방금 등록한 약(taken=false)

                    [주의] 등록한 보호자가 회원 탈퇴하면 그 보호자가 등록한 약도 함께 중지되며,
                    남은 보호자에게 중지 안내 알림이 발송됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 완료. data: 등록된 약"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "필수값 누락 또는 길이·범위 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자", content = @Content)
    })
    @PostMapping("/api/guardian/ward/{wardId}/medication")
    public ResponseEntity<ApiResponse<MedicationItem>> create(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID") @PathVariable String wardId,
            @Valid @RequestBody MedicationCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(guardianMedicationService.create(guardianId, wardId, request)));
    }

    @Operation(summary = "약 삭제 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    복약 일정을 삭제합니다. ACTIVE 연결된 피보호자의 약만 삭제할 수 있으며,
                    등록한 보호자가 아니어도 연결되어 있으면 삭제할 수 있습니다
                    (보호자가 여럿일 때 서로의 등록을 정리할 수 있어야 하므로).

                    [동작] 지난 복용 이력은 남기고 일정만 중지합니다(soft delete).
                    삭제된 약은 이후 조회에 나오지 않으며, 같은 약을 다시 쓰려면 새로 등록합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자의 약", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 이미 삭제된 약", content = @Content)
    })
    @DeleteMapping("/api/guardian/medication/{medicationId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "약 ID") @PathVariable Long medicationId) {
        guardianMedicationService.delete(guardianId, medicationId);
        return ResponseEntity.ok(ApiResponse.ok("약이 삭제되었습니다."));
    }

    @Operation(summary = "피보호자 복약 알림 설정 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    피보호자의 복약 알림 ON/OFF 상태를 반환합니다. 설정한 적이 없으면 기본값 true입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자", content = @Content)
    })
    @GetMapping("/api/guardian/ward/{wardId}/medication-setting")
    public ResponseEntity<ApiResponse<MedicationSettingResponse>> getSetting(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID") @PathVariable String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(guardianMedicationService.getSetting(guardianId, wardId)));
    }

    @Operation(summary = "피보호자 복약 알림 설정 변경 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    피보호자의 복약 알림을 켜거나 끕니다(화면의 알림 토글).

                    [요청 바디] 둘 다 선택 — 보내지 않은 항목은 기존값이 유지됩니다.
                    - alarmEnabled: true = 알림 켜기, false = 끄기
                    - remindAgainEnabled: 복용 체크를 안 했을 때 15분 뒤 한 번 더 알릴지 (기본 true)

                    [발송 동작]
                    - 복용 시각이 되면 피보호자 본인에게 알림이 갑니다(FCM·문자 — 사용자 알림 설정을 따름).
                    - 이미 복용 체크를 했으면 보내지 않습니다.
                    - 정각을 놓쳐도 30분까지는 발송하고, 그 뒤로는 건너뜁니다(밤늦게 아침 약 알림 방지).
                    - remindAgainEnabled=true면 체크가 없을 때 15분 뒤 한 번 더 보냅니다(최대 1회).

                    [주의]
                    - 설정은 피보호자 계정에 붙습니다 — 보호자가 여러 명이면 한 명이 끈 결과가 모두에게 보입니다.
                    - 이 설정은 복약 알림에만 적용됩니다. SOS 등 필수 알림에는 영향이 없습니다.
                    - 문자를 켜둔 경우 재알림까지 켜면 한 번 복용에 문자가 2건까지 나갑니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 완료. data: 적용된 설정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자", content = @Content)
    })
    @PutMapping("/api/guardian/ward/{wardId}/medication-setting")
    public ResponseEntity<ApiResponse<MedicationSettingResponse>> updateSetting(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID") @PathVariable String wardId,
            @Valid @RequestBody MedicationSettingUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(guardianMedicationService.updateSetting(
                guardianId, wardId, request.alarmEnabled(), request.remindAgainEnabled())));
    }

    @Operation(summary = "내 복약 알림 수신 설정 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    보호자 본인이 받을 복약 알림 설정을 반환합니다. 설정한 적이 없으면 기본값 true입니다.

                    ※ 피보호자별 설정(/api/guardian/ward/{wardId}/medication-setting)과 다릅니다 —
                      저쪽은 "피보호자에게 무엇을 보낼지", 이쪽은 "내가 무엇을 받을지"입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/medication-alert-setting")
    public ResponseEntity<ApiResponse<GuardianMedicationAlertSettingResponse>> getAlertSetting(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(GuardianMedicationAlertSettingResponse.of(
                guardianMedicationSettingService.isMissedAlertEnabled(guardianId))));
    }

    @Operation(summary = "내 복약 알림 수신 설정 변경 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [요청 바디] missedAlertEnabled (선택 — 생략 시 기존값 유지)
                    - true: 피보호자가 복약을 체크하지 않은 날 저녁에 요약 알림을 받습니다.
                    - false: 받지 않습니다.

                    [발송 동작]
                    - 매일 21시(KST)에 한 번, 피보호자별로 요약해 보냅니다(FCM·문자 — 사용자 알림 설정을 따름).
                    - 21시까지 복용 시각이 지난 약만 집계합니다 — 취침 전 22시 약은 그날 요약에 포함되지 않습니다.
                    - 체크되지 않은 약이 하나도 없으면 보내지 않습니다.
                    - 같은 피보호자 건은 하루 한 번만 발송됩니다.

                    [주의]
                    - 이 알림은 "약을 안 드셨다"가 아니라 "체크되지 않았다"를 알립니다 —
                      실제로는 복용하고 체크만 안 한 경우가 있을 수 있습니다.
                    - 이 설정은 복약 요약 알림에만 적용됩니다. SOS 등 필수 알림에는 영향이 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 완료. data: 적용된 설정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @PutMapping("/api/guardian/medication-alert-setting")
    public ResponseEntity<ApiResponse<GuardianMedicationAlertSettingResponse>> updateAlertSetting(
            @AuthenticationPrincipal String guardianId,
            @Valid @RequestBody GuardianMedicationAlertSettingRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(GuardianMedicationAlertSettingResponse.of(
                guardianMedicationSettingService.updateMissedAlertEnabled(
                        guardianId, request.missedAlertEnabled()))));
    }
}
