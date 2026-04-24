package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.*;
import kr.silverbridge.main.domain.admin.service.AdminAnnouncementService;
import kr.silverbridge.main.domain.admin.service.AdminAuditLogService;
import kr.silverbridge.main.domain.admin.service.AdminConnectionService;
import kr.silverbridge.main.domain.admin.service.AdminService;
import kr.silverbridge.main.domain.admin.service.AdminUserService;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "관리자", description = "관리자 전용 API. 모든 요청에 관리자 계정 토큰 필요: Authorization: Bearer {accessToken}")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {

    private final AdminService adminService;
    private final AdminUserService adminUserService;
    private final AdminConnectionService adminConnectionService;
    private final AdminAnnouncementService adminAnnouncementService;
    private final AdminAuditLogService auditLogService;

    // =============================================
    // 사용자 관리
    // =============================================

    @Operation(
            summary = "사용자 목록 조회",
            description = """
                    피보호자(WARD) / 보호자(GUARDIAN) 목록을 조회합니다.
                    ADMIN 계정은 목록에서 제외됩니다.

                    [필터]
                    - role: WARD(피보호자만) / GUARDIAN(보호자만) / 미입력(전체)

                    [정렬]
                    - 가입일 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/user/select")
    public ApiResponse<List<UserSummaryResponse>> getUsers(
            @Parameter(description = "역할 필터 (WARD / GUARDIAN / 미입력: 전체)")
            @RequestParam(required = false) Role role) {
        return ApiResponse.ok(adminUserService.getUsers(role));
    }

    @Operation(
            summary = "사용자 상세 조회",
            description = """
                    특정 사용자의 전체 정보를 조회합니다.
                    전화번호, 가입 경로(LOCAL/KAKAO), 최근 로그인 일시 등을 포함합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 상세 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/user/select/detail/{userId}")
    public ApiResponse<UserDetailResponse> getUser(
            @Parameter(description = "사용자 ID") @PathVariable String userId) {
        return ApiResponse.ok(adminUserService.getUser(userId));
    }

    @Operation(
            summary = "사용자 상태 변경",
            description = """
                    피보호자/보호자 계정을 활성화(ACTIVE) 또는 비활성화(INACTIVE) 처리합니다.

                    [주의사항]
                    - 비활성화된 계정은 로그인이 즉시 차단됩니다.
                    - ADMIN 계정은 상태 변경 불가합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "상태 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "status 값 누락 또는 ACTIVE/INACTIVE 외의 값", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 계정은 상태 변경 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PatchMapping("/user/status-change/{userId}")
    public ApiResponse<Void> updateUserStatus(
            @Parameter(description = "사용자 ID") @PathVariable String userId,
            @Valid @RequestBody UserStatusUpdateRequest request,
            @AuthenticationPrincipal String adminId) {
        adminUserService.updateUserStatus(userId, request, adminId);
        return ApiResponse.ok("사용자 상태가 변경되었습니다.");
    }

    @Operation(
            summary = "사용자 역할 변경",
            description = """
                    피보호자(WARD) ↔ 보호자(GUARDIAN) 역할을 변경합니다.

                    [주의사항]
                    - ADMIN 계정은 역할 변경 불가합니다.
                    - ADMIN으로의 변경은 허용되지 않습니다.
                    - 역할 변경 시 해당 사용자의 ACTIVE/PENDING 연결이 자동으로 CANCELLED 처리됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역할 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "role 값 누락 / ADMIN으로 변경 시도", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 계정은 역할 변경 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PatchMapping("/user/role-change/{userId}")
    public ApiResponse<Void> updateUserRole(
            @Parameter(description = "사용자 ID") @PathVariable String userId,
            @Valid @RequestBody UserRoleUpdateRequest request,
            @AuthenticationPrincipal String adminId) {
        adminUserService.updateUserRole(userId, request, adminId);
        return ApiResponse.ok("사용자 역할이 변경되었습니다.");
    }

    @Operation(
            summary = "사용자 강제 탈퇴",
            description = """
                    피보호자/보호자 계정을 영구 삭제합니다.

                    [주의사항]
                    - 삭제된 계정은 복구할 수 없습니다.
                    - 해당 사용자의 연결 관계(connections), 게임 결과(game_results)도 함께 삭제됩니다.
                    - ADMIN 계정은 삭제 불가합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 탈퇴 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN 계정은 삭제 불가", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/user/delete/{userId}")
    public ApiResponse<Void> forceDeleteUser(
            @Parameter(description = "사용자 ID") @PathVariable String userId,
            @AuthenticationPrincipal String adminId) {
        adminUserService.forceDeleteUser(userId, adminId);
        return ApiResponse.ok("사용자가 강제 탈퇴 처리되었습니다.");
    }

    // =============================================
    // 연결 관계 관리
    // =============================================

    @Operation(
            summary = "전체 연결 관계 조회",
            description = """
                    보호자-피보호자 전체 연결 관계를 조회합니다.
                    PENDING(수락 대기) / ACTIVE(연결됨) / CANCELLED(해제됨) 상태 모두 포함됩니다.

                    [정렬]
                    - 연결 요청일 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 관계 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/user/connection/select")
    public ApiResponse<List<ConnectionResponse>> getConnections() {
        return ApiResponse.ok(adminConnectionService.getConnections());
    }

    @Operation(
            summary = "특정 보호자의 연결 목록 조회",
            description = """
                    특정 보호자에 연결된 피보호자 목록을 조회합니다.
                    PENDING / ACTIVE / CANCELLED 상태 모두 포함됩니다.

                    [주의사항]
                    - guardianId는 반드시 GUARDIAN 역할 사용자여야 합니다. WARD ID 입력 시 400 반환.

                    [정렬]
                    - 연결 요청일 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "guardianId가 GUARDIAN 역할이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/user/connection/guardian/{guardianId}")
    public ApiResponse<List<ConnectionResponse>> getConnectionsByGuardian(
            @Parameter(description = "보호자 ID (GUARDIAN 역할만 가능)") @PathVariable String guardianId) {
        return ApiResponse.ok(adminConnectionService.getConnectionsByGuardian(guardianId));
    }

    @Operation(
            summary = "보호자-피보호자 강제 연결",
            description = """
                    관리자가 보호자와 피보호자를 즉시 ACTIVE 상태로 연결합니다.
                    고객센터 접수 확인 후 사용하세요.

                    [조건]
                    - guardianId는 GUARDIAN 역할, wardId는 WARD 역할이어야 합니다.
                    - 두 사용자 모두 ACTIVE 상태여야 합니다 (비활성화 계정 연결 불가).
                    - 이미 PENDING 또는 ACTIVE 연결이 존재하면 409 반환.
                    - CANCELLED 상태였던 경우 재연결 가능합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 연결 성공. 생성된 연결 정보 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "역할 불일치 / 비활성화 계정", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 PENDING 또는 ACTIVE 연결이 존재함", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/user/connection/force")
    public ApiResponse<ConnectionResponse> forceConnect(
            @Valid @RequestBody AdminForceConnectRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminConnectionService.forceConnect(request, adminId));
    }

    @Operation(
            summary = "보호자-피보호자 강제 연결 해제",
            description = """
                    관리자가 보호자-피보호자 연결을 강제로 해제(CANCELLED)합니다.
                    해제 후 동일 쌍의 재연결은 POST /api/admin/user/connection/force 로 가능합니다.

                    [요청 파라미터]
                    - connectionId: 해제할 연결 ID (ConnectionResponse.id 값)
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 해제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 연결 관계", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/user/disconnection/force")
    public ApiResponse<Void> forceDisconnect(
            @Parameter(description = "연결 ID (ConnectionResponse.id)") @RequestParam Long connectionId,
            @AuthenticationPrincipal String adminId) {
        adminConnectionService.forceDisconnect(connectionId, adminId);
        return ApiResponse.ok("연결이 해제되었습니다.");
    }

    // =============================================
    // 이상감지 이벤트 조회
    // =============================================

    @Operation(
            summary = "이상감지 이벤트 조회",
            description = """
                    AI 서버가 감지하여 저장된 이상감지 이벤트 이력을 조회합니다.

                    [이벤트 유형]
                    - FIRE: 화재 감지
                    - WEAPON: 흉기 감지
                    - FALL: 낙상 감지

                    [필터 조건] (모두 선택 사항)
                    - guardianId: 해당 보호자의 ACTIVE 연결 피보호자 이벤트만 조회. GUARDIAN 역할만 허용.
                    - startDate / endDate: 감지 일시(detectedAt) 범위. ISO 8601 형식 (예: 2025-01-01T00:00:00+09:00)

                    [피보호자 탈퇴 시]
                    wardName, wardEmail 은 null 로 반환됩니다.

                    [정렬]
                    - 감지 일시 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이상감지 이벤트 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "guardianId가 GUARDIAN 역할이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자 (guardianId 입력 시)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/event/abnormal")
    public ApiResponse<List<AnomalyEventResponse>> getAnomalyEvents(
            @Parameter(description = "보호자 ID (미입력 시 전체 조회, GUARDIAN 역할만 허용)")
            @RequestParam(required = false) String guardianId,
            @Parameter(description = "조회 시작 일시 (ISO 8601, 예: 2025-01-01T00:00:00+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
            @Parameter(description = "조회 종료 일시 (ISO 8601, 예: 2025-12-31T23:59:59+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate) {
        return ApiResponse.ok(adminService.getAnomalyEvents(guardianId, startDate, endDate));
    }

    // =============================================
    // 게임 결과 조회
    // =============================================

    @Operation(
            summary = "게임 결과 조회",
            description = """
                    피보호자의 게임 플레이 결과 이력을 조회합니다.

                    [게임 유형]
                    - MATCHING: 짝 맞추기
                    - WORD_QUIZ: 단어 퀴즈
                    - ADDITION: 덧셈
                    - SUBTRACTION: 뺄셈

                    [필터 조건] (모두 선택 사항)
                    - userId: 특정 피보호자의 결과만 조회. WARD 역할만 허용.
                    - gameType: 게임 유형 필터. 미입력 시 전체 유형 조회.
                    - startDate / endDate: 플레이 일시(playedAt) 범위. ISO 8601 형식 (예: 2025-01-01T00:00:00+09:00)

                    [정렬]
                    - 플레이 일시 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게임 결과 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "userId가 WARD 역할이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자 (userId 입력 시)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/game/result/select")
    public ApiResponse<List<GameResultResponse>> getGameResults(
            @Parameter(description = "피보호자 ID (미입력 시 전체 조회, WARD 역할만 허용)")
            @RequestParam(required = false) String userId,
            @Parameter(description = "게임 유형 (MATCHING / WORD_QUIZ / ADDITION / SUBTRACTION, 미입력 시 전체)")
            @RequestParam(required = false) GameType gameType,
            @Parameter(description = "조회 시작 일시 (ISO 8601, 예: 2025-01-01T00:00:00+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
            @Parameter(description = "조회 종료 일시 (ISO 8601, 예: 2025-12-31T23:59:59+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate) {
        return ApiResponse.ok(adminService.getGameResults(userId, gameType, startDate, endDate));
    }

    // =============================================
    // 공지 관리
    // =============================================

    @Operation(
            summary = "공지 목록 조회",
            description = """
                    공지 목록을 조회합니다.

                    [작성자 탈퇴 시]
                    authorName 은 null 로 반환됩니다.

                    [정렬]
                    - 작성 일시 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/announcement/select")
    public ApiResponse<List<AnnouncementResponse>> getAnnouncements() {
        return ApiResponse.ok(adminAnnouncementService.getAnnouncements());
    }

    @Operation(
            summary = "공지 상세 조회",
            description = "공지 ID로 단건 상세 조회합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/announcement/select/detail/{id}")
    public ApiResponse<AnnouncementResponse> getAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id) {
        return ApiResponse.ok(adminAnnouncementService.getAnnouncement(id));
    }

    @Operation(
            summary = "공지 등록",
            description = "새 공지를 등록합니다. 등록 즉시 게시됩니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록된 공지 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 또는 content 누락 / title 200자 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PostMapping("/announcement/create")
    public ApiResponse<AnnouncementResponse> createAnnouncement(
            @Valid @RequestBody AnnouncementCreateRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminAnnouncementService.createAnnouncement(request, adminId));
    }

    @Operation(
            summary = "공지 수정",
            description = "공지의 제목과 내용을 수정합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 공지 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 또는 content 누락 / title 200자 초과", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @PutMapping("/announcement/update/{id}")
    public ApiResponse<AnnouncementResponse> updateAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id,
            @Valid @RequestBody AnnouncementUpdateRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminAnnouncementService.updateAnnouncement(id, request, adminId));
    }

    @Operation(
            summary = "공지 삭제",
            description = """
                    공지를 영구 삭제합니다.

                    [주의사항]
                    - 삭제된 공지는 복구할 수 없습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @DeleteMapping("/announcement/delete/{id}")
    public ApiResponse<Void> deleteAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id,
            @AuthenticationPrincipal String adminId) {
        adminAnnouncementService.deleteAnnouncement(id, adminId);
        return ApiResponse.ok("공지가 삭제되었습니다.");
    }

    // =============================================
    // 사용자 접근 로그 조회
    // =============================================

    @Operation(
            summary = "사용자 접근 로그 조회",
            description = """
                    전체 접속 로그를 조회합니다.

                    [포함 이력]
                    - LOGIN: 일반 로그인
                    - KAKAO_LOGIN: 카카오 로그인
                    - LOGOUT: 로그아웃
                    - TOKEN_ISSUE: Access Token 재발급
                    - PASSWORD_RESET: 비밀번호 재설정

                    [정렬]
                    - 발생 일시 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "접속 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/accesslog/select")
    public ApiResponse<List<AccessLogResponse>> getAccessLogs() {
        return ApiResponse.ok(adminService.getAccessLogs());
    }

    // =============================================
    // 관리자 행동 감사 로그 조회
    // =============================================

    @Operation(
            summary = "관리자 행동 감사 로그 조회",
            description = """
                    관리자가 수행한 모든 행동 이력을 조회합니다.
                    사용자 상태/역할 변경, 강제 탈퇴, 연결 관리, 공지 CRUD 등 모든 변경 작업이 기록됩니다.

                    [기록되는 행동 유형]
                    - USER_STATUS_CHANGE: 사용자 상태 변경 (ACTIVE/INACTIVE)
                    - USER_ROLE_CHANGE: 사용자 역할 변경 + 연결 자동 해제
                    - USER_FORCE_DELETE: 사용자 강제 탈퇴
                    - FORCE_CONNECT: 보호자-피보호자 강제 연결
                    - FORCE_DISCONNECT: 보호자-피보호자 강제 연결 해제
                    - ANNOUNCEMENT_CREATE: 공지 등록
                    - ANNOUNCEMENT_UPDATE: 공지 수정
                    - ANNOUNCEMENT_DELETE: 공지 삭제

                    [정렬]
                    - 발생 일시 내림차순
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "감사 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/audit/select")
    public ApiResponse<List<AdminAuditLogResponse>> getAuditLogs() {
        return ApiResponse.ok(auditLogService.getLogs());
    }
}
