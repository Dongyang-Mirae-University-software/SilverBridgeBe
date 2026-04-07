package kr.gosky.sso.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.gosky.sso.domain.admin.dto.*;
import kr.gosky.sso.domain.admin.service.AdminService;
import kr.gosky.sso.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@Tag(name = "관리자", description = "사용자 관리 및 접속 로그 조회 API (ADMIN 권한 필요)")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "사용자 목록 조회", description = "전체 사용자 목록을 페이징하여 조회합니다. 기본 정렬: 가입일 내림차순, 페이지 크기: 20")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음")
    })
    @GetMapping("/users")
    public ApiResponse<Page<UserSummaryResponse>> getUsers(
            @Parameter(description = "페이징 정보 (page, size, sort)")
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getUsers(pageable));
    }

    @Operation(summary = "사용자 상세 조회", description = "특정 사용자의 상세 정보를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 상세 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자")
    })
    @GetMapping("/users/{userId}")
    public ApiResponse<UserDetailResponse> getUser(
            @Parameter(description = "사용자 UUID") @PathVariable String userId) {
        return ApiResponse.ok(adminService.getUser(userId));
    }

    @Operation(summary = "사용자 상태 변경", description = "사용자 계정을 활성화(ACTIVE) 또는 비활성화(INACTIVE) 처리합니다. 비활성화된 계정은 로그인이 차단됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상태 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 유효성 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자")
    })
    @PatchMapping("/users/{userId}/status")
    public ApiResponse<Void> updateUserStatus(
            @Parameter(description = "사용자 UUID") @PathVariable String userId,
            @Valid @RequestBody UserStatusUpdateRequest request) {
        adminService.updateUserStatus(userId, request);
        return ApiResponse.ok("사용자 상태가 변경되었습니다.");
    }

    @Operation(summary = "접속 로그 조회", description = "전체 접속 로그를 페이징하여 조회합니다. 로그인/로그아웃/토큰 재발급/비밀번호 재설정 이력을 포함합니다. 기본 페이지 크기: 50")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "접속 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음")
    })
    @GetMapping("/access-logs")
    public ApiResponse<Page<AccessLogResponse>> getAccessLogs(
            @Parameter(description = "페이징 정보 (page, size, sort)")
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getAccessLogs(pageable));
    }
}
