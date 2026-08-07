package kr.silverbridge.main.domain.medication.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.medication.dto.MedicationItem;
import kr.silverbridge.main.domain.medication.dto.TodayMedicationResponse;
import kr.silverbridge.main.domain.medication.service.WardMedicationService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 피보호자용 복약 API — 오늘의 일정 조회와 복용 체크/해제.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('WARD')")}로 WARD만 접근 가능(GUARDIAN/ADMIN 403).
 *
 * <p><b>약 등록·삭제 API는 여기 없다</b> — 등록·삭제는 보호자 전용({@code /api/guardian/...})이다.
 * 반대로 <b>복용 체크는 이 경로가 유일</b>하며, 보호자에게는 체크 API가 없다.</p>
 */
@Tag(name = "피보호자 - 복약")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardMedicationController {

    private final WardMedicationService wardMedicationService;

    @Operation(summary = "오늘의 복약 일정 조회 (피보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    본인의 오늘 복약 일정을 복용 시각 순으로 반환합니다.

                    [응답] data: TodayMedicationResponse
                    - doseDate: 조회 기준일(KST)
                    - takenCount / totalCount: "0/3회 완료"의 두 숫자
                    - medications[]: medicationId / name / timeSlot / doseTime / doseAmount / memo
                      / taken(체크 여부) / takenAt(체크 시각)

                    [주의]
                    - 등록된 약이 없으면 medications는 빈 배열, 0/0입니다.
                    - 기준일은 항상 한국 시간(KST)입니다 — 자정을 넘기면 새 날짜의 일정(전부 미복용)이 됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "오늘의 복약 일정 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/ward/medication/today")
    public ResponseEntity<ApiResponse<TodayMedicationResponse>> getToday(
            @AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(wardMedicationService.getToday(wardId)));
    }

    @Operation(summary = "복용 체크 (피보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    오늘 그 약을 복용했다고 체크합니다. <b>체크는 피보호자 본인만 할 수 있으며</b>,
                    체크해야 보호자 화면에 "복용함"으로 보입니다.

                    [동작]
                    1. 오늘 날짜(KST)로 복용 기록을 남깁니다.
                    2. 커밋 후 비동기로 WebSocket 발송 — ACTIVE 보호자 전원 + 본인(다른 기기 동기화):
                       /topic/{userId}/medication-taken
                       (medicationId, wardId, medicationName, doseDate, taken, takenAt)
                       ※ 푸시·문자는 발송하지 않습니다(하루 여러 번 일어나는 일상 동작이라 소음).

                    [주의]
                    - 이미 체크된 약을 다시 체크해도 오류가 아니며 기존 기록이 그대로 유지됩니다(멱등).
                      이때는 보호자에게 알림이 다시 가지 않습니다.
                    - 체크할 수 있는 날짜는 오늘뿐입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "체크 완료. data: 갱신된 항목"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요 / 본인의 약이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 삭제된 약", content = @Content)
    })
    @PostMapping("/api/ward/medication/{medicationId}/intake")
    public ResponseEntity<ApiResponse<MedicationItem>> markTaken(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "약 ID") @PathVariable Long medicationId) {
        return ResponseEntity.ok(ApiResponse.ok(wardMedicationService.markTaken(wardId, medicationId)));
    }

    @Operation(summary = "복용 체크 해제 (피보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    잘못 누른 체크를 되돌립니다(오늘 것만).

                    [동작] 오늘 복용 기록을 지우고, 커밋 후 WebSocket으로 보호자 화면을 갱신합니다
                    (taken=false).

                    [주의] 체크되어 있지 않은 약을 해제해도 오류가 아닙니다(멱등).
                    이때는 보호자에게 알림이 가지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 완료. data: 갱신된 항목"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "피보호자 권한 필요 / 본인의 약이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않거나 삭제된 약", content = @Content)
    })
    @DeleteMapping("/api/ward/medication/{medicationId}/intake")
    public ResponseEntity<ApiResponse<MedicationItem>> unmarkTaken(
            @AuthenticationPrincipal String wardId,
            @Parameter(description = "약 ID") @PathVariable Long medicationId) {
        return ResponseEntity.ok(ApiResponse.ok(wardMedicationService.unmarkTaken(wardId, medicationId)));
    }
}
