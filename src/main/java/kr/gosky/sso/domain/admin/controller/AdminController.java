package kr.gosky.sso.domain.admin.controller;

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

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // 사용자 목록 조회
    // 200 OK — 페이징된 사용자 목록 반환
    @GetMapping("/users")
    public ApiResponse<Page<UserSummaryResponse>> getUsers(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getUsers(pageable));
    }

    // 사용자 상세 조회
    // 200 OK — 사용자 상세 정보 반환
    // 404 Not Found — 존재하지 않는 사용자
    @GetMapping("/users/{userId}")
    public ApiResponse<UserDetailResponse> getUser(@PathVariable String userId) {
        return ApiResponse.ok(adminService.getUser(userId));
    }

    // 사용자 상태 변경 (활성화 / 비활성화)
    // 200 OK — 변경 성공
    // 404 Not Found — 존재하지 않는 사용자
    @PatchMapping("/users/{userId}/status")
    public ApiResponse<Void> updateUserStatus(@PathVariable String userId,
                                              @Valid @RequestBody UserStatusUpdateRequest request) {
        adminService.updateUserStatus(userId, request);
        return ApiResponse.ok("사용자 상태가 변경되었습니다.");
    }

    // 접속 로그 조회
    // 200 OK — 페이징된 접속 로그 반환
    @GetMapping("/access-logs")
    public ApiResponse<Page<AccessLogResponse>> getAccessLogs(
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getAccessLogs(pageable));
    }
}
