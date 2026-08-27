package kr.silverbridge.main.domain.sos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.sos.dto.SosHistoryItem;
import kr.silverbridge.main.domain.sos.service.GuardianSosService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호자용 SOS 이력 조회 API.
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 *
 * <p>조회 전용이다 - 처리 결과(ACK)를 남기는 API는 기능 철회로 제거했다(2026-08-26, V39).</p>
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
                    - content[].sosEventId / wardId / wardName / triggeredAt / location
                    - content[].triggerType: SOS_BUTTON(긴급 SOS 버튼) · GUARDIAN_CALL(보호자에게 직접 전화)
                    - totalElements 로 "최근 N건" 표기가 가능합니다.

                    [주의]
                    - 이력은 "언제·어떤 경로로 발생했는지"까지만 답합니다. 처리 결과(ACK)는 기록하지 않습니다.
                    - 연결이 해제되면 그 피보호자의 과거 이력도 조회되지 않습니다.
                    - 화면 문구는 프론트가 조립합니다 - 서버는 원자값만 줍니다.
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
}
