package kr.silverbridge.main.domain.connection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.connection.dto.ConnectionPriorityUpdateRequest;
import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
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
@RequestMapping("/api/ward/connections")
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
                    """)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ConnectionResponse>>> getMyGuardians(
            @AuthenticationPrincipal String wardId,
            @PageableDefault(sort = "priority", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.getMyGuardians(wardId, pageable)));
    }

    @Operation(summary = "받은 페어링 요청 목록",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    보호자가 피보호자에게 보낸 PENDING 상태 요청 목록입니다.
                    """)
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<Page<ConnectionResponse>>> getPendingRequests(
            @AuthenticationPrincipal String wardId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                connectionService.getPendingRequestsForWard(wardId, pageable)));
    }

    @Operation(summary = "보호자에게 페어링 요청",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [페어링 전체 흐름]
                    1. POST /api/ward/connections              → 피보호자가 보호자에게 요청
                    2. 보호자 앱에 WebSocket(/topic/{guardianId}/connection-request) + FCM 알림 전송
                    3. POST /api/guardian/connections/{id}/accept → 보호자가 수락
                    """)
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> requestConnection(
            @AuthenticationPrincipal String wardId,
            @Valid @RequestBody ConnectionRequestDto request) {
        connectionService.requestConnectionAsWard(wardId, request);
        return ResponseEntity.ok(ApiResponse.ok("페어링 요청을 전송했습니다."));
    }

    @Operation(summary = "페어링 요청 수락 (보호자가 보낸 요청)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    보호자가 보낸 요청만 수락 가능합니다.
                    수락 시 보호자에게 WebSocket + FCM 알림이 전송됩니다.
                    """)
    @PostMapping("/{connectionId}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptConnection(
            @AuthenticationPrincipal String wardId,
            @PathVariable Long connectionId) {
        connectionService.acceptConnectionAsWard(wardId, connectionId);
        return ResponseEntity.ok(ApiResponse.ok("연결 요청을 수락했습니다."));
    }

    @Operation(summary = "페어링 요청 거절 또는 연결 해제",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}
                    """)
    @DeleteMapping("/{connectionId}")
    public ResponseEntity<ApiResponse<Void>> cancelConnection(
            @AuthenticationPrincipal String wardId,
            @PathVariable Long connectionId) {
        connectionService.cancelConnectionAsWard(wardId, connectionId);
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "ACTIVE 상태가 아닌 연결"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 연결의 피보호자가 아님")
    })
    @PatchMapping("/{connectionId}/priority")
    public ResponseEntity<ApiResponse<Void>> updatePriority(
            @AuthenticationPrincipal String wardId,
            @PathVariable Long connectionId,
            @Valid @RequestBody ConnectionPriorityUpdateRequest request) {
        connectionService.updatePriority(wardId, connectionId, request.getPriority());
        return ResponseEntity.ok(ApiResponse.ok("우선순위를 변경했습니다."));
    }
}