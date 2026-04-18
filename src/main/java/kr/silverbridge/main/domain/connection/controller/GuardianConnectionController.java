package kr.silverbridge.main.domain.connection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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


@Tag(name = "보호자")
@RestController
@RequestMapping("/api/guardian/connections")
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianConnectionController {

    private final ConnectionService connectionService;

    @Operation(summary = "내 피보호자 목록 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (최대 100)
                    - sort: 정렬 기준 (기본값: createdAt,desc)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ACTIVE 상태 연결 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ConnectionResponse>>> getMyWards(
            @AuthenticationPrincipal String guardianId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(connectionService.getMyWards(guardianId, pageable)));
    }

    @Operation(summary = "피보호자에게 페어링 요청",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [페어링 전체 흐름]
                    1. POST /api/guardian/connections          → 보호자가 피보호자에게 요청
                    2. 피보호자 앱에 WebSocket(/topic/{wardId}/connection-request) + FCM 알림 전송
                    3. POST /api/ward/connections/{id}/accept  → 피보호자가 수락
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 전송 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "역할 불일치 또는 자기 자신과 연결 시도"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 연결되어 있거나 요청 중인 관계")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> requestConnection(
            @AuthenticationPrincipal String guardianId,
            @Valid @RequestBody ConnectionRequestDto request) {
        connectionService.requestConnectionAsGuardian(guardianId, request);
        return ResponseEntity.ok(ApiResponse.ok("페어링 요청을 전송했습니다."));
    }

    @Operation(summary = "페어링 요청 거절 또는 연결 해제",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    PENDING 상태: 요청 거절
                    ACTIVE 상태: 연결 해제 (피보호자에게 WebSocket + FCM 알림 전송)
                    """)
    @DeleteMapping("/{connectionId}")
    public ResponseEntity<ApiResponse<Void>> cancelConnection(
            @AuthenticationPrincipal String guardianId,
            @PathVariable Long connectionId) {
        connectionService.cancelConnectionAsGuardian(guardianId, connectionId);
        return ResponseEntity.ok(ApiResponse.ok("연결을 해제했습니다."));
    }
}