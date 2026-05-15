package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kr.silverbridge.main.domain.admin.dto.AdminUserCountsResponse;
import kr.silverbridge.main.domain.admin.dto.AdminUserSearchPageResponse;
import kr.silverbridge.main.domain.admin.dto.UserDetailResponse;
import kr.silverbridge.main.domain.admin.dto.UserRoleUpdateRequest;
import kr.silverbridge.main.domain.admin.dto.UserStatusUpdateRequest;
import kr.silverbridge.main.domain.admin.dto.UserSummaryResponse;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@Validated
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "사용자 목록 조회",
            description = """
                    피보호자(WARD) / 보호자(GUARDIAN) 목록을 조회합니다.
                    ADMIN 계정은 목록에서 제외됩니다.

                    [필터]
                    - role: WARD(피보호자만) / GUARDIAN(보호자만) / 미입력(전체)

                    [정렬]
                    - 가입일 내림차순
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select")
    public ApiResponse<List<UserSummaryResponse>> getUsers(
            @Parameter(description = "역할 필터 (WARD / GUARDIAN / 미입력: 전체)")
            @RequestParam(required = false) Role role) {
        return ApiResponse.ok(adminUserService.getUsers(role));
    }

    @Operation(summary = "사용자 통합 검색 (회원관리 화면)",
            description = """
                    회원관리 화면용 통합 검색 API. ADMIN 계정도 결과에 포함됩니다.

                    [검색 키워드]
                    - keyword: email / name / phone 부분 일치 (LIKE)
                    - 미입력 시 키워드 조건 무시

                    [필터]
                    - role: WARD / GUARDIAN / ADMIN (미입력 시 전체)
                    - status: ACTIVE / INACTIVE (미입력 시 전체)

                    [페이징]
                    - page: 0-based 페이지 번호 (기본 0)
                    - size: 한 페이지 크기 (기본 10, 최대 100)

                    [정렬]
                    - 가입 일시 내림차순 (고정)

                    [응답]
                    - content: 회원 목록 (id, email, name, role, phone, status)
                    - page, size, totalElements, totalPages
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "검색 결과 페이지 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "page/size 범위 위반", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/search")
    public ApiResponse<AdminUserSearchPageResponse> searchUsers(
            @Parameter(description = "검색 키워드 (email/name/phone 부분 일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "역할 필터 (WARD / GUARDIAN / ADMIN / 미입력: 전체)")
            @RequestParam(required = false) Role role,
            @Parameter(description = "상태 필터 (ACTIVE / INACTIVE / 미입력: 전체)")
            @RequestParam(required = false) Status status,
            @Parameter(description = "페이지 번호 (0-based)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "한 페이지 크기 (1~100)", example = "10")
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(adminUserService.searchUsers(keyword, role, status, page, size));
    }

    @Operation(summary = "회원 탭별 건수 조회",
            description = """
                    회원관리 화면 상단 탭에 표시할 인원 수를 반환합니다.

                    [응답 필드]
                    - total: 전체 사용자 수 (ADMIN 포함)
                    - ward: 피보호자 수
                    - guardian: 보호자 수
                    - admin: 관리자 수
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "탭별 건수 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 권한 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/counts")
    public ApiResponse<AdminUserCountsResponse> getUserCounts() {
        return ApiResponse.ok(adminUserService.getUserCounts());
    }

    @Operation(summary = "사용자 상세 조회",
            description = """
                    특정 사용자의 전체 정보를 조회합니다.
                    전화번호, 가입 경로(LOCAL/KAKAO), 최근 로그인 일시 등을 포함합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 상세 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/select/detail/{userId}")
    public ApiResponse<UserDetailResponse> getUser(
            @Parameter(description = "사용자 ID") @PathVariable String userId) {
        return ApiResponse.ok(adminUserService.getUser(userId));
    }

    @Operation(summary = "사용자 상태 변경",
            description = """
                    피보호자/보호자 계정을 활성화(ACTIVE) 또는 비활성화(INACTIVE) 처리합니다.

                    [주의사항]
                    - 비활성화된 계정은 로그인이 즉시 차단됩니다.
                    - ADMIN 계정은 상태 변경 불가합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상태 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "status 값 누락 또는 ACTIVE/INACTIVE 외의 값", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 계정은 상태 변경 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PatchMapping("/status-change/{userId}")
    public ApiResponse<Void> updateUserStatus(
            @Parameter(description = "사용자 ID") @PathVariable String userId,
            @Valid @RequestBody UserStatusUpdateRequest request,
            @AuthenticationPrincipal String adminId) {
        adminUserService.updateUserStatus(userId, request, adminId);
        return ApiResponse.ok("사용자 상태가 변경되었습니다.");
    }

    @Operation(summary = "사용자 역할 변경",
            description = """
                    피보호자(WARD) ↔ 보호자(GUARDIAN) 역할을 변경합니다.

                    [주의사항]
                    - ADMIN 계정은 역할 변경 불가합니다.
                    - ADMIN으로의 변경은 허용되지 않습니다.
                    - 역할 변경 시 해당 사용자의 ACTIVE/PENDING 연결이 자동으로 CANCELLED 처리됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역할 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "role 값 누락 / ADMIN으로 변경 시도", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 계정은 역할 변경 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PatchMapping("/role-change/{userId}")
    public ApiResponse<Void> updateUserRole(
            @Parameter(description = "사용자 ID") @PathVariable String userId,
            @Valid @RequestBody UserRoleUpdateRequest request,
            @AuthenticationPrincipal String adminId) {
        adminUserService.updateUserRole(userId, request, adminId);
        return ApiResponse.ok("사용자 역할이 변경되었습니다.");
    }

    @Operation(summary = "사용자 강제 탈퇴",
            description = """
                    피보호자/보호자 계정을 영구 삭제합니다.

                    [주의사항]
                    - 삭제된 계정은 복구할 수 없습니다.
                    - 해당 사용자의 연결 관계(connections), 게임 결과(game_results)도 함께 삭제됩니다.
                    - ADMIN 계정은 삭제 불가합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 탈퇴 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 계정은 삭제 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/delete/{userId}")
    public ApiResponse<Void> forceDeleteUser(
            @Parameter(description = "사용자 ID") @PathVariable String userId,
            @AuthenticationPrincipal String adminId) {
        adminUserService.forceDeleteUser(userId, adminId);
        return ApiResponse.ok("사용자가 강제 탈퇴 처리되었습니다.");
    }
}
