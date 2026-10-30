package kr.silverbridge.main.domain.sos.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.sos.dto.SosHistoryPage;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.domain.sos.service.GuardianSosService;
import kr.silverbridge.main.global.response.ApiResponse;
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

                    [triggerType 파라미터] (선택)
                    - SOS_BUTTON(긴급 SOS 버튼) · GUARDIAN_CALL(보호자에게 직접 전화) 중 하나만 조회
                    - 생략: 전체 경로

                    [group 파라미터] (선택, 기본 false)
                    - true: 연타를 한 칸으로 묶어 반환합니다("2번 연달아 누름").
                      같은 피보호자·같은 발생 경로·같은 전화받은 보호자(called_guardian_id)의 다음 누름이 "앞 누름"과 1분(60초) 이내면 같은 묶음입니다.
                      묶음 기준은 피보호자 + 발생 경로 + 전화받은 보호자이며, 간격은 sos.history-group-gap-seconds입니다.
                      시계 기준 칸이 아니라 연쇄라, 30초 간격으로 5분 동안 누르면 한 묶음입니다. 정확히 60초 간격도 같은 묶음입니다.
                    - 묶음 항목의 sosEventId·triggeredAt·location은 묶음의 최신 건 값이고,
                      pressCount(누름 수)·firstTriggeredAt(첫 누름)·lastTriggeredAt(마지막 누름 = triggeredAt)이 더해집니다.
                    - page·totalElements·totalPages와 counts(all·sosButton·guardianCall)는 모두 "묶음 수"입니다.
                    - false·생략: 지금과 같이 누를 때마다 한 줄(위 세 필드는 응답에 없음), counts는 누름 수입니다.
                    - 묶음은 표시 전용입니다 - 푸시 문구의 "최근 10분 내 N번째"와 기준이 다릅니다.

                    [응답] data: SosHistoryPage
                    - content[].sosEventId / wardId / wardName / triggeredAt / location
                    - content[].triggerType: SOS_BUTTON(긴급 SOS 버튼) · GUARDIAN_CALL(보호자에게 직접 전화)
                    - content[].calledGuardianName: 보호자 연락(GUARDIAN_CALL)에서 전화받은 보호자 이름.
                      피보호자가 전화받은 보호자를 함께 보냈고 그 보호자가 지금도 연결돼 있을 때만 있습니다
                      (SOS 버튼·옛 이력·연결 해제/탈퇴한 보호자면 필드가 없습니다)
                    - content[].pressCount / firstTriggeredAt / lastTriggeredAt: group=true일 때만 (위 group 설명)
                    - page / size / totalElements / totalPages / last: 기존 페이지 응답과 같은 필드 (triggerType 필터 기준)
                    - counts.all / counts.sosButton / counts.guardianCall: 경로별 전체 건수
                      (triggerType·페이지와 무관, 조회 범위 피보호자 전체 기준 - 탭 숫자는 이 값으로 그립니다)

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
    public ResponseEntity<ApiResponse<SosHistoryPage>> getSosHistory(
            @AuthenticationPrincipal String guardianId,
            @Parameter(description = "특정 피보호자만 조회 (생략 시 연결된 피보호자 전원)") @RequestParam(required = false) String wardId,
            @Parameter(description = "발생 경로 필터 (생략 시 전체)") @RequestParam(required = false) SosTriggerType triggerType,
            @Parameter(description = "true면 연타를 묶음 단위로 반환 (앞 누름과 1분 이내·같은 경로, 기본 false)")
            @RequestParam(defaultValue = "false") boolean group,
            @Parameter(description = "페이지 번호 (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (최대 50)") @RequestParam(defaultValue = "20") int size) {
        SosHistoryPage history = group
                ? guardianSosService.getGroupedHistory(guardianId, wardId, triggerType, page, size)
                : guardianSosService.getHistory(guardianId, wardId, triggerType, page, size);
        return ResponseEntity.ok(ApiResponse.ok(history));
    }
}
