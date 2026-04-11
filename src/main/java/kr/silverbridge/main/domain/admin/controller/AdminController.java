package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.*;
import kr.silverbridge.main.domain.admin.service.AdminService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "관리자")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "사용자 목록 조회", description = "role 파라미터로 피보호자(WARD) 또는 보호자(GUARDIAN) 목록을 조회합니다. 미입력 시 WARD+GUARDIAN 전체 조회. 기본 정렬: 가입일 내림차순, 페이지 크기: 20")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/users")
    public ApiResponse<Page<UserSummaryResponse>> getUsers(
            @Parameter(description = "역할 필터 (WARD: 피보호자, GUARDIAN: 보호자, 미입력: 전체)")
            @RequestParam(required = false) Role role,
            @Parameter(description = "페이징 정보 (page, size, sort)")
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getUsers(role, pageable));
    }

    @Operation(summary = "사용자 상세 조회", description = "특정 사용자의 상세 정보를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 상세 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/users/{userId}")
    public ApiResponse<UserDetailResponse> getUser(
            @Parameter(description = "사용자 UUID") @PathVariable String userId) {
        return ApiResponse.ok(adminService.getUser(userId));
    }

    @Operation(summary = "사용자 상태 변경", description = "피보호자/보호자 계정을 활성화(ACTIVE) 또는 비활성화(INACTIVE) 처리합니다. 비활성화된 계정은 로그인이 차단됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상태 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/users/{userId}/status")
    public ApiResponse<Void> updateUserStatus(
            @Parameter(description = "사용자 UUID") @PathVariable String userId,
            @Valid @RequestBody UserStatusUpdateRequest request) {
        adminService.updateUserStatus(userId, request);
        return ApiResponse.ok("사용자 상태가 변경되었습니다.");
    }

    @Operation(summary = "사용자 역할 변경", description = "피보호자(WARD) ↔ 보호자(GUARDIAN) 역할을 변경합니다. ADMIN 계정은 변경 불가합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역할 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효하지 않은 역할값 (ADMIN으로 변경 시도 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음 또는 ADMIN 계정 변경 시도"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/users/{userId}/role")
    public ApiResponse<Void> updateUserRole(
            @Parameter(description = "사용자 UUID") @PathVariable String userId,
            @Valid @RequestBody UserRoleUpdateRequest request) {
        adminService.updateUserRole(userId, request);
        return ApiResponse.ok("사용자 역할이 변경되었습니다.");
    }

    @Operation(summary = "사용자 강제 탈퇴", description = "피보호자/보호자 계정을 강제로 삭제합니다. 삭제된 계정은 복구할 수 없습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 탈퇴 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> forceDeleteUser(
            @Parameter(description = "사용자 UUID") @PathVariable String userId) {
        adminService.forceDeleteUser(userId);
        return ApiResponse.ok("사용자가 강제 탈퇴 처리되었습니다.");
    }

    @Operation(summary = "전체 연결 관계 조회", description = "보호자-피보호자 전체 연결 관계를 페이징하여 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 관계 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/connections")
    public ApiResponse<Page<ConnectionResponse>> getConnections(
            @Parameter(description = "페이징 정보 (page, size, sort)")
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getConnections(pageable));
    }

    @Operation(summary = "특정 보호자의 피보호자 목록 조회", description = "특정 보호자에 연결된 피보호자 목록을 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "피보호자 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 보호자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/connections/guardian/{guardianId}")
    public ApiResponse<Page<ConnectionResponse>> getConnectionsByGuardian(
            @Parameter(description = "보호자 UUID") @PathVariable String guardianId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getConnectionsByGuardian(guardianId, pageable));
    }

    @Operation(summary = "보호자-피보호자 강제 연결", description = "관리자가 보호자와 피보호자를 즉시 연결합니다. 고객센터 확인 후 사용하세요.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 연결 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "역할 불일치 (보호자/피보호자 역할이 맞지 않음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 연결되어 있거나 요청 중인 관계"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/connections")
    public ApiResponse<ConnectionResponse> forceConnect(
            @Valid @RequestBody AdminForceConnectRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminService.forceConnect(request, adminId));
    }

    @Operation(summary = "보호자-피보호자 강제 연결 해제", description = "관리자가 보호자-피보호자 연결을 강제로 해제합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 해제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 연결 관계"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/connections/{connectionId}")
    public ApiResponse<Void> forceDisconnect(
            @Parameter(description = "연결 ID") @PathVariable Long connectionId) {
        adminService.forceDisconnect(connectionId);
        return ApiResponse.ok("연결이 해제되었습니다.");
    }

    @Operation(summary = "접속 로그 조회", description = "전체 접속 로그를 페이징하여 조회합니다. 로그인/로그아웃/토큰 재발급/비밀번호 재설정 이력을 포함합니다. 기본 페이지 크기: 50")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "접속 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/access-logs")
    public ApiResponse<Page<AccessLogResponse>> getAccessLogs(
            @Parameter(description = "페이징 정보 (page, size, sort)")
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getAccessLogs(pageable));
    }
}
