package kr.silverbridge.main.domain.sos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.sos.dto.SosAckRequest;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.service.GuardianSosService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호자용 SOS 이력 조회·처리(ACK) API.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 */
@Tag(name = "보호자 - SOS 이력")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianSosController {

    private final GuardianSosService guardianSosService;

    @Operation(summary = "피보호자 SOS 이력 조회 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자의 SOS 발생 이력을 최신순으로 반환합니다.

                    [wardId 파라미터]
                    - 지정: 해당 피보호자의 이력만 (연결이 ACTIVE가 아니면 403)
                    - 생략: ACTIVE 연결된 피보호자 전원의 이력을 합쳐서 최신순 (연결이 없으면 빈 페이지)

                    [응답] data: PageResponse<SosHistoryItem>
                    - content[].sosEventId / wardId / wardName / triggeredAt
                    - content[].ackStatus: SAFE_CONFIRMED(안전 확인) · EMERGENCY_DISPATCHED(응급 출동) · null(미처리)
                    - content[].ackNote / acknowledgedByName / acknowledgedAt
                    - totalElements 로 "최근 N건" 표기가 가능합니다.

                    [주의]
                    - 연결이 해제되면 그 피보호자의 과거 이력도 조회되지 않습니다.
                    - 화면 문구("통화 연결 · 안전 확인" 등)는 프론트가 조립합니다 — 서버는 원자값만 줍니다.
                    - size는 최대 50으로 제한됩니다(초과 요청은 50으로 처리).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SOS 이력 페이지 반환 (연결된 피보호자가 없으면 빈 content)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자 지정", content = @Content)
    })
    @GetMapping("/api/guardian/sos/history")
    public ResponseEntity<ApiResponse<PageResponse<SosHistoryItem>>> getSosHistory(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "특정 피보호자만 조회 (생략 시 연결된 피보호자 전원)") @RequestParam(required = false) String wardId,
            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                guardianSosService.getHistory(guardianId, wardId, page, size)));
    }

    @Operation(summary = "SOS 처리 결과 기록 (보호자 전용)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    보호자가 SOS 이력 한 건의 처리 결과를 남깁니다.

                    [요청 바디]
                    - ackStatus (필수): SAFE_CONFIRMED(안전 확인) · EMERGENCY_DISPATCHED(응급 출동)
                    - ackNote (선택, 200자 이내): 처리 메모 (예 "통화 연결 · 안전 확인")

                    [동작]
                    1. 이력에 처리 결과·보호자·시각을 기록합니다. 이미 처리된 건도 덮어씁니다(재기록 허용).
                    2. 커밋 후 비동기로 WebSocket 발송 — 같은 피보호자의 ACTIVE 보호자 전원 + 피보호자 본인:
                       /topic/{userId}/sos-acknowledged (sosEventId, wardId, ackStatus, acknowledgedBy, acknowledgedByName)
                       ※ 푸시·문자는 발송하지 않습니다(상황 종료 후 상태 갱신).

                    [주의]
                    - SOS 보호자 알림은 필수 알림이라 처리 여부와 무관하게 항상 발송됩니다 — 이 API로 알림을 끌 수 없습니다.
                    - 건당 처리 결과는 하나입니다(보호자별로 따로 남지 않고 마지막 처리로 갱신).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 결과 기록 완료. data: 갱신된 이력 항목"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "ackStatus 누락 또는 메모 길이 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / 연결되지 않은 피보호자의 이력", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SOS 이력을 찾을 수 없음", content = @Content)
    })
    @PatchMapping("/api/guardian/sos/{sosEventId}/ack")
    public ResponseEntity<ApiResponse<SosHistoryItem>> acknowledgeSos(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "SOS 이력 ID") @PathVariable Long sosEventId,
            @Valid @RequestBody SosAckRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                guardianSosService.acknowledge(guardianId, sosEventId, request)));
    }
}
