package kr.silverbridge.main.domain.connection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.dto.PendingConnectionResponse;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "피보호자")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardConnectionController {

    private final ConnectionService connectionService;

    @Operation(summary = "내 보호자 리스트 조회 (ACTIVE, 연결 오래된 순)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    피보호자웹 "내 보호자 리스트" 카드용.
                    ACTIVE 상태 연결 목록을 연결 생성일(createdAt) 오름차순으로 반환합니다.
                    전화번호·주소는 ACTIVE이므로 노출됩니다.
                    """)
    @GetMapping("/api/ward/connection/active")
    public ResponseEntity<ApiResponse<List<ConnectionResponse>>> getActiveGuardians(
            @AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.getActiveGuardians(wardId)));
    }

    @Operation(summary = "요청온 목록 조회 (PENDING, 요청일 최신순)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    피보호자웹 "요청온 목록" 카드용.
                    보호자가 보낸 PENDING 연결 요청을 요청일(createdAt) 내림차순으로 반환합니다.
                    수락 전이므로 전화번호는 마스킹(010****5678)되고, 주소는 노출되지 않습니다.
                    수락은 POST /api/ward/connection/{id}/accept,
                    거절은 DELETE /api/ward/connection/request/{id}/refusal 을 사용합니다.
                    """)
    @GetMapping("/api/ward/connection/pending")
    public ResponseEntity<ApiResponse<List<PendingConnectionResponse>>> getPendingRequests(
            @AuthenticationPrincipal String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.getPendingRequests(wardId)));
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
}
