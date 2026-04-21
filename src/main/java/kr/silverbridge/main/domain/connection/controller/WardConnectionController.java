package kr.silverbridge.main.domain.connection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.connection.dto.ConnectionPriorityUpdateRequest;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "피보호자")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardConnectionController {

    private final ConnectionService connectionService;

    @Operation(summary = "내 보호자 목록 조회 (우선순위 순)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 상태 연결 목록을 우선순위(priority) 오름차순으로 반환합니다.
                    긴급통화 시 1순위 보호자에게 먼저 연결 시도합니다.

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (최대 100)
                    """)
    @GetMapping("/api/ward/connection/select")
    public ResponseEntity<ApiResponse<Page<ConnectionResponse>>> getMyGuardians(
            @AuthenticationPrincipal String wardId,
            @PageableDefault(sort = "priority", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.getMyGuardians(wardId, pageable)));
    }

    @Operation(summary = "보호자 요청 수락",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [페어링 전체 흐름]
                    1. POST /api/guardian/connection/request      → 보호자가 피보호자 ID 입력 후 요청
                    2. 피보호자 앱에 WebSocket(/topic/{wardId}/connection-request) + FCM 알림 전송
                    3. POST /api/ward/connection/{id}/accept      → 피보호자가 수락 (현재 API)
                    수락 시 보호자에게 WebSocket + FCM 알림이 전송됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수락 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "PENDING 상태가 아닌 연결", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 연결의 피보호자가 아님", content = @Content)
    })
    @PostMapping("/api/ward/connection/{connectionId}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptConnection(
            @AuthenticationPrincipal String wardId,
            @PathVariable Long connectionId) {
        connectionService.acceptConnectionAsWard(wardId, connectionId);
        return ResponseEntity.ok(ApiResponse.ok("연결 요청을 수락했습니다."));
    }

    @Operation(summary = "보호자 요청 거절 (PENDING 상태)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    PENDING 상태인 보호자 요청을 거절합니다.
                    이미 ACTIVE인 연결은 이 API로 해제할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거절 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "PENDING 상태가 아닌 연결", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 연결의 피보호자가 아님", content = @Content)
    })
    @DeleteMapping("/api/ward/connection/request/{connectionId}/refusal")
    public ResponseEntity<ApiResponse<Void>> refuseConnection(
            @AuthenticationPrincipal String wardId,
            @PathVariable Long connectionId) {
        connectionService.refuseConnectionAsWard(wardId, connectionId);
        return ResponseEntity.ok(ApiResponse.ok("연결 요청을 거절했습니다."));
    }

    @Operation(summary = "연결 해제 (ACTIVE 상태)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 상태인 연결을 해제합니다.
                    해제 시 보호자에게 WebSocket + FCM 알림이 전송됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "ACTIVE 상태가 아닌 연결", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 연결의 피보호자가 아님", content = @Content)
    })
    @DeleteMapping("/api/ward/disconnection/{connectionId}")
    public ResponseEntity<ApiResponse<Void>> disconnect(
            @AuthenticationPrincipal String wardId,
            @PathVariable Long connectionId) {
        connectionService.disconnectAsWard(wardId, connectionId);
        return ResponseEntity.ok(ApiResponse.ok("연결을 해제했습니다."));
    }

    @Operation(summary = "보호자 통화 우선순위 변경",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    긴급통화(SOS) 시 연결된 보호자 중 priority가 낮은 순서대로 WebRTC 연결을 시도합니다.
                    priority=1 이 1순위입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "우선순위 변경 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "ACTIVE 상태가 아닌 연결", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 연결의 피보호자가 아님", content = @Content)
    })
    @PatchMapping("/api/ward/call/priority/{connectionId}")
    public ResponseEntity<ApiResponse<Void>> updatePriority(
            @AuthenticationPrincipal String wardId,
            @PathVariable Long connectionId,
            @Valid @RequestBody ConnectionPriorityUpdateRequest request) {
        connectionService.updatePriority(wardId, connectionId, request.getPriority());
        return ResponseEntity.ok(ApiResponse.ok("우선순위를 변경했습니다."));
    }
}
