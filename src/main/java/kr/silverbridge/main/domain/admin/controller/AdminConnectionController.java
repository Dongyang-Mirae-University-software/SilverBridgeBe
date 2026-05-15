package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.AdminConnectionResponse;
import kr.silverbridge.main.domain.admin.dto.AdminForceConnectRequest;
import kr.silverbridge.main.domain.admin.service.AdminConnectionService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "관리자 - 회원관리")
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminConnectionController {

    private final AdminConnectionService adminConnectionService;

    @Operation(summary = "전체 연결 관계 조회",
            description = """
                    보호자-피보호자 전체 연결 관계를 조회합니다.
                    PENDING(수락 대기) / ACTIVE(연결됨) / CANCELLED(해제됨) 상태 모두 포함됩니다.

                    [정렬]
                    - 연결 요청일 내림차순
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 관계 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/connection/select")
    public ApiResponse<List<AdminConnectionResponse>> getConnections() {
        return ApiResponse.ok(adminConnectionService.getConnections());
    }

    @Operation(summary = "특정 보호자의 연결 목록 조회",
            description = """
                    특정 보호자에 연결된 피보호자 목록을 조회합니다.
                    PENDING / ACTIVE / CANCELLED 상태 모두 포함됩니다.

                    [주의사항]
                    - guardianId는 반드시 GUARDIAN 역할 사용자여야 합니다. WARD ID 입력 시 400 반환.

                    [정렬]
                    - 연결 요청일 내림차순
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "guardianId가 GUARDIAN 역할이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/connection/guardian/{guardianId}")
    public ApiResponse<List<AdminConnectionResponse>> getConnectionsByGuardian(
            @Parameter(description = "보호자 ID (GUARDIAN 역할만 가능)") @PathVariable String guardianId) {
        return ApiResponse.ok(adminConnectionService.getConnectionsByGuardian(guardianId));
    }

    @Operation(summary = "보호자-피보호자 강제 연결",
            description = """
                    관리자가 보호자와 피보호자를 즉시 ACTIVE 상태로 연결합니다.
                    고객센터 접수 확인 후 사용하세요.

                    [조건]
                    - guardianId는 GUARDIAN 역할, wardId는 WARD 역할이어야 합니다.
                    - 두 사용자 모두 ACTIVE 상태여야 합니다 (비활성화 계정 연결 불가).
                    - 이미 PENDING 또는 ACTIVE 연결이 존재하면 409 반환.
                    - CANCELLED 상태였던 경우 재연결 가능합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 연결 성공. 생성된 연결 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "역할 불일치 / 비활성화 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 PENDING 또는 ACTIVE 연결이 존재함", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/connection/force")
    public ApiResponse<AdminConnectionResponse> forceConnect(
            @Valid @RequestBody AdminForceConnectRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminConnectionService.forceConnect(request, adminId));
    }

    @Operation(summary = "보호자-피보호자 강제 연결 해제",
            description = """
                    관리자가 보호자-피보호자 연결을 강제로 해제(CANCELLED)합니다.
                    해제 후 동일 쌍의 재연결은 POST /api/admin/user/connection/force 로 가능합니다.

                    [요청 파라미터]
                    - connectionId: 해제할 연결 ID (AdminConnectionResponse.id 값)
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 해제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 연결 관계", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/disconnection/force")
    public ApiResponse<Void> forceDisconnect(
            @Parameter(description = "연결 ID (AdminConnectionResponse.id)") @RequestParam Long connectionId,
            @AuthenticationPrincipal String adminId) {
        adminConnectionService.forceDisconnect(connectionId, adminId);
        return ApiResponse.ok("연결이 해제되었습니다.");
    }
}
