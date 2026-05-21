package kr.silverbridge.main.domain.connection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.connection.dto.ConnectionRequestDto;
import kr.silverbridge.main.domain.connection.dto.ConnectionResponse;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.global.response.ApiResponse;
import kr.silverbridge.main.global.security.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "보호자")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianConnectionController {

    private final ConnectionService connectionService;
    private final RateLimitService rateLimitService;

    @Operation(summary = "내 피보호자 목록 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE(연결됨) + PENDING(수락 대기) 상태 연결 목록을 최신 요청순으로 반환합니다.
                    status 필드로 상태를 구분하여 UI에서 "수락 대기 중" 표시에 활용하세요.
                    거절·취소된 이력까지 함께 보려면 /api/guardian/connection/requests 를 사용하세요.

                    상대방 전화번호·주소는 ACTIVE 상태에서만 채워지고, PENDING/CANCELLED에서는 null로 반환됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ACTIVE + PENDING 상태 연결 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/connection/select")
    public ResponseEntity<ApiResponse<List<ConnectionResponse>>> getMyWards(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.getMyWards(guardianId)));
    }

    @Operation(summary = "내가 보낸 연결 요청 이력 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    보호자가 보낸 모든 연결 요청을 PENDING + ACTIVE + CANCELLED 전부 포함하여 최신 요청순으로 반환합니다.
                    "피보호자 등록" 화면의 "요청 내역" 테이블(거절·취소된 이력까지 표시)에 사용합니다.

                    상대방 전화번호·주소는 ACTIVE 상태에서만 채워지고, PENDING/CANCELLED에서는 null로 반환됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전체 상태 연결 요청 이력 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요", content = @Content)
    })
    @GetMapping("/api/guardian/connection/requests")
    public ResponseEntity<ApiResponse<List<ConnectionResponse>>> getMyConnectionRequests(
            @AuthenticationPrincipal String guardianId) {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.getMyConnectionRequests(guardianId)));
    }

    @Operation(summary = "피보호자에게 페어링 요청",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [페어링 전체 흐름]
                    1. POST /api/guardian/connection/request     → 보호자가 피보호자 ID 입력 후 요청
                    2. 피보호자 앱에 WebSocket(/topic/{wardId}/connection-request) + FCM 알림 전송
                    3. POST /api/ward/connection/{id}/accept     → 피보호자가 수락
                    4. DELETE /api/ward/connection/request/{id}/refusal → 피보호자가 거절
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 전송 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "역할 불일치 또는 자기 자신과 연결 시도", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 연결되어 있거나 요청 중인 관계", content = @Content)
    })
    @PostMapping("/api/guardian/connection/request")
    public ResponseEntity<ApiResponse<Void>> requestConnection(
            @AuthenticationPrincipal String guardianId,
            @Valid @RequestBody ConnectionRequestDto request) {
        // 동일 보호자의 연속 페어링 요청 제한 (피보호자 스팸 및 userId 열거 방지)
        rateLimitService.check("connection-request", guardianId);
        connectionService.requestConnectionAsGuardian(guardianId, request);
        return ResponseEntity.ok(ApiResponse.ok("페어링 요청을 전송했습니다."));
    }

    @Operation(summary = "페어링 요청 취소 (수락 전 PENDING 상태)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    보호자가 피보호자에게 보낸 PENDING 상태의 요청을 취소합니다.
                    이미 ACTIVE인 연결은 이 API로 해제할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 취소 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PENDING 상태가 아닌 연결(이미 처리됨)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 연결의 보호자가 아님", content = @Content)
    })
    @DeleteMapping("/api/guardian/connection/cancel/{connectionId}")
    public ResponseEntity<ApiResponse<Void>> cancelPendingRequest(
            @AuthenticationPrincipal String guardianId,
            @PathVariable Long connectionId) {
        connectionService.cancelPendingAsGuardian(guardianId, connectionId);
        return ResponseEntity.ok(ApiResponse.ok("페어링 요청을 취소했습니다."));
    }

    @Operation(summary = "연결 해제 (ACTIVE 상태)",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 상태인 연결을 해제합니다.
                    해제 시 피보호자에게 WebSocket + FCM 알림이 전송됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ACTIVE 상태가 아닌 연결(이미 처리됨)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "해당 연결의 보호자가 아님", content = @Content)
    })
    @DeleteMapping("/api/guardian/connection/disconnection/{connectionId}")
    public ResponseEntity<ApiResponse<Void>> disconnect(
            @AuthenticationPrincipal String guardianId,
            @PathVariable Long connectionId) {
        connectionService.disconnectAsGuardian(guardianId, connectionId);
        return ResponseEntity.ok(ApiResponse.ok("연결을 해제했습니다."));
    }
}
