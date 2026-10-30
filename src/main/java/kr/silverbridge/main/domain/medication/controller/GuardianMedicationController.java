package kr.silverbridge.main.domain.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.medication.dto.GuardianMedicationAlertSettingRequest;
import kr.silverbridge.main.domain.medication.dto.GuardianMedicationAlertSettingResponse;
import kr.silverbridge.main.domain.medication.dto.MedicationBatchCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationCreateRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationDeleteBatchRequest;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.MedicationUpdateRequest;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
@Tag(name = "보호자 - 복약")
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
                      **doseTime을 보내면 저장되는 시간대는 그 시각으로 정해집니다**(04:00~10:59 아침 / 11:00~15:59 점심 /
                      16:00~20:59 저녁 / 그 외 취침 전, 일괄 등록과 같은 규칙). timeSlot과 어긋나도 400이 아니라 시각 기준입니다.
                    - doseAmount (선택, 0.5~99, 0.5 단위): 복용량(정). 반 알은 0.5, 정수(1, 2)도 그대로 받는다. 생략 시 1
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

    @Operation(summary = "약 여러 개 × 복용 시각 여러 개 한 번에 추가 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    "약 추가" 화면처럼 약 여러 개와 드시는 시각 여러 개를 함께 등록합니다. drugs × times 만큼 약이 만들어집니다
                    (예: 약 6개 × 시각 2개 = 12건). **전부 성공 또는 전부 실패**입니다 - 하나라도 검증에 걸리면 아무것도 등록되지 않습니다.

                    [요청 바디]
                    - drugs (필수, 1~20개): [{ name (100자 이내), doseAmount (선택, 0.5~99, 0.5 단위 - 반 알은 0.5, 생략 시 1) }]
                    - times (필수, 1~6개): [{ doseTime: "HH:mm:ss" }]
                    - memo (선택, 100자 이내): 모든 약에 붙는 복용 안내

                    시간대(timeSlot)는 서버가 시각으로 정합니다: 04:00~10:59 아침 / 11:00~15:59 점심 / 16:00~20:59 저녁 / 그 외 취침 전.
                    같은 요청 안에 같은 이름(대소문자 무시)이나 같은 시각이 두 번 있으면 400입니다. 이미 등록된 약과 이름이 같아도 등록됩니다(단건 등록과 같음).

                    [응답] data: MedicationItem[] - 등록된 약 전부(복용 시각 순, 같은 시각 안에서는 요청한 약 순서, taken=false)

                    [오류 code] MEDICATION_NOT_AUTHORIZED(403) / MEDICATION_BATCH_DUPLICATE_NAME(400) / MEDICATION_BATCH_DUPLICATE_TIME(400) / INVALID_INPUT(400, 개수·이름·복용량)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 완료. data: 등록된 약 목록"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "개수·이름·복용량 검증 실패 또는 같은 요청 안의 중복", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자", content = @Content)
    })
    @PostMapping("/api/guardian/ward/{wardId}/medication/batch")
    public ResponseEntity<ApiResponse<List<MedicationItem>>> createBatch(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID") @PathVariable String wardId,
            @Valid @RequestBody MedicationBatchCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(guardianMedicationService.createBatch(guardianId, wardId, request)));
    }

    @Operation(summary = "약 수정 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    등록된 약의 정보를 수정합니다. **부분 수정**이라 보낸 필드만 바뀌고, 생략한 필드는 그대로입니다.
                    ACTIVE 연결된 피보호자의 약만 수정할 수 있으며, 등록한 보호자가 아니어도 됩니다.

                    [요청 바디] 모두 선택 — null인 필드는 변경하지 않습니다.
                    - name (1~100자): 약 이름
                    - timeSlot: MORNING · LUNCH · DINNER · BEDTIME
                    - doseTime: 복용 시각
                    - doseAmount (0.5~99, 0.5 단위): 복용량(정). 반 알은 0.5. 0.3·0·100은 400
                    - memo (100자 이내): 복용 안내. **빈 문자열을 보내면 메모가 삭제**됩니다
                      (null은 "변경 안 함"이라 메모를 지울 수 없습니다).

                    [시간대만 바꾼 경우]
                    doseTime을 함께 주지 않으면 **새 시간대의 기본 시각**으로 갱신됩니다
                    (아침 08:00 → 저녁으로 바꾸면 18:00). 그러지 않으면 "저녁 08:00"이 남습니다.

                    [시간대는 시각으로 정해집니다]
                    timeSlot이나 doseTime을 보내면 저장되는 시간대는 **최종 복용 시각으로 다시 정해집니다**
                    (04:00~10:59 아침 / 11:00~15:59 점심 / 16:00~20:59 저녁 / 그 외 취침 전). 둘이 어긋나도 400이 아니라
                    시각 기준입니다(예: LUNCH + 01:40 → BEDTIME). 이름·복용량·메모만 보내면 시간대는 바뀌지 않습니다.

                    [복용 시각이 바뀌면 오늘 알림이 다시 나갑니다]
                    - 그 약의 당일 발송 기록(최초·재알림)을 지워 새 시각 기준으로 다시 판정합니다.
                      08:00을 20:00으로 고쳤는데 오늘 저녁에 안 울리는 문제를 막기 위함입니다.
                    - 이미 복용 체크를 했다면 여전히 알림은 나가지 않습니다(체크는 지워지지 않습니다).
                    - 이름·메모만 바꾼 경우에는 발송 기록을 건드리지 않습니다.

                    [응답] data: MedicationItem — 수정된 약(오늘 복용 체크 상태 포함)

                    [주의] 피보호자를 바꾸는 것은 지원하지 않습니다 — 삭제 후 새로 등록해 주세요.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 완료. data: 수정된 약"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "길이·범위 위반", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자의 약", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 삭제된 약", content = @Content)
    })
    @PatchMapping("/api/guardian/medication/{medicationId}")
    public ResponseEntity<ApiResponse<MedicationItem>> update(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "약 ID") @PathVariable Long medicationId,
            @Valid @RequestBody MedicationUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                guardianMedicationService.update(guardianId, medicationId, request)));
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

    @Operation(summary = "약 여러 개 삭제 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    한 피보호자의 약을 한 번에 삭제합니다("아침 약 모두 삭제"·"약 전부 삭제").

                    [요청] { "medicationIds": [11, 12, 13] } - 1~100개, 같은 ID 중복 불가(400)
                    화면에 보이는 약 ID를 보냅니다("전부" 신호가 아니다 - 다른 보호자가 방금 추가한 약은 지우지 않는다).

                    [동작]
                    - <b>전부 성공 또는 전부 실패</b>입니다. 연결되지 않은 피보호자(403), 다른 피보호자의 약이 섞임(403),
                      없는 약(404)이면 아무것도 지워지지 않습니다.
                    - 이미 삭제된 약은 건너뜁니다(오류 아님).
                    - 지난 복용 이력은 남기고 일정만 중지합니다(soft delete). 알림은 보내지 않습니다.

                    [응답] data: 이번에 삭제한 약 수
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 완료. data: 삭제한 약 수"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "목록이 비었거나 100개 초과 / 같은 약 중복", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자 / 다른 피보호자의 약", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 약", content = @Content)
    })
    @PostMapping("/api/guardian/ward/{wardId}/medication/delete-batch")
    public ResponseEntity<ApiResponse<Integer>> deleteBatch(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID") @PathVariable String wardId,
            @Valid @RequestBody MedicationDeleteBatchRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                guardianMedicationService.deleteBatch(guardianId, wardId, request.medicationIds())));
    }

    @Operation(summary = "피보호자별 미복용 요약 수신 설정 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    그 피보호자에 대해 "내가" 받을 미복용 요약 설정을 반환합니다.
                    설정한 적이 없으면 기본값(missedAlertEnabled=true, missedAlertTime=21:00)입니다.

                    ※ missedAlertTime은 항상 실제 적용되는 시각입니다(미설정이면 기본값이 담깁니다).

                    ※ 이 값은 GET /api/guardian/medication 응답의 카드에도 함께 실립니다 -
                      목록 화면에서는 피보호자 수만큼 이 API를 부르지 마세요.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 또는 연결되지 않은 피보호자", content = @Content)
    })
    @GetMapping("/api/guardian/ward/{wardId}/medication-alert-setting")
    public ResponseEntity<ApiResponse<GuardianMedicationAlertSettingResponse>> getAlertSetting(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID") @PathVariable String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(GuardianMedicationAlertSettingResponse.of(
                wardId, guardianMedicationSettingService.getSetting(guardianId, wardId))));
    }

    @Operation(summary = "피보호자별 미복용 요약 수신 설정 변경 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [요청 바디] 두 필드 모두 선택 - 생략(null)하면 기존값을 유지합니다.
                    - missedAlertEnabled: true면 이 피보호자가 복약을 체크하지 않은 날 요약을 받습니다.
                    - missedAlertTime: 요약을 받을 시각(KST, 분 단위 · 예 "19:30:00").
                      기본값(21:00)으로 되돌리려면 21:00을 직접 지정하세요.

                    [발송 동작]
                    - 지정한 시각에 한 번, 이 피보호자 건을 요약해 보냅니다(FCM·문자 - 사용자 알림 설정을 따름).
                    - ⚠️ 지정한 시각은 집계 상한이기도 합니다. 그 시각까지 복용 시각이 지난 약만 셉니다 -
                      예를 들어 19:00으로 지정하면 저녁 20시·취침 전 22시 약은 그날 요약에 포함되지 않습니다.
                      아직 먹을 때가 아닌 약을 미복용으로 알리지 않기 위한 동작입니다.
                      → 그 피보호자의 마지막 복용 시각을 덮도록 시각을 잡아 주세요.
                    - 체크되지 않은 약이 하나도 없으면 보내지 않습니다.
                    - 같은 피보호자 건은 하루 한 번만 발송됩니다.
                    - 서버 점검 등으로 지정 시각을 놓치면 2시간 안에서만 늦게 발송하고, 그 뒤로는 그날 건을 넘깁니다.

                    [주의]
                    - 이 설정은 나에게만 적용됩니다. 같은 피보호자를 보는 다른 보호자는 자기 시각을 따로 가집니다.
                    - 이 알림은 "약을 안 드셨다"가 아니라 "체크되지 않았다"를 알립니다 -
                      실제로는 복용하고 체크만 안 한 경우가 있을 수 있습니다.
                    - 이 설정은 복약 요약 알림에만 적용됩니다. SOS 등 필수 알림에는 영향이 없습니다.
                    - 보호자 계정 전체 토글(GET /api/guardian/notification-type-setting 의 medication.enabled)이
                      꺼져 있으면 이 값이 true여도 요약은 오지 않습니다(둘 다 켜져야 발송). 화면에서 안내해 주세요.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 완료. data: 적용된 설정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 또는 연결되지 않은 피보호자", content = @Content)
    })
    @PutMapping("/api/guardian/ward/{wardId}/medication-alert-setting")
    public ResponseEntity<ApiResponse<GuardianMedicationAlertSettingResponse>> updateAlertSetting(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "피보호자 ID") @PathVariable String wardId,
            @Valid @RequestBody GuardianMedicationAlertSettingRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(GuardianMedicationAlertSettingResponse.of(
                wardId, guardianMedicationSettingService.update(
                        guardianId, wardId, request.missedAlertEnabled(), request.missedAlertTime()))));
    }
}
