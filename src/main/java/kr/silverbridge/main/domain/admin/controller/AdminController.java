package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.admin.dto.*;
import kr.silverbridge.main.domain.admin.service.AdminService;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@Tag(name = "관리자", description = "관리자 전용 API. 모든 요청에 관리자 계정 토큰 필요: Authorization: Bearer {accessToken}")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminController {

    private final AdminService adminService;

    // =============================================
    // 사용자 관리
    // =============================================

    @Operation(
            summary = "사용자 목록 조회",
            description = """
                    피보호자(WARD) / 보호자(GUARDIAN) 목록을 페이징하여 조회합니다.
                    ADMIN 계정은 목록에서 제외됩니다.

                    [필터]
                    - role: WARD(피보호자만) / GUARDIAN(보호자만) / 미입력(전체)

                    [기본 정렬]
                    - 가입일 내림차순, 페이지 크기 20

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 20)
                    - sort: 정렬 기준 (예: sort=createdAt,desc / sort=name,asc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 목록 배열
                    data.totalElements → 전체 항목 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 번호 (0부터)
                    data.size          → 페이지당 크기
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "사용자 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/users")
    public ApiResponse<Page<UserSummaryResponse>> getUsers(
            @Parameter(description = "역할 필터 (WARD / GUARDIAN / 미입력: 전체)")
            @RequestParam(required = false) Role role,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getUsers(role, pageable));
    }

    @Operation(
            summary = "사용자 상세 조회",
            description = """
                    특정 사용자의 전체 정보를 조회합니다.
                    전화번호, 가입 경로(LOCAL/KAKAO), 이메일 인증 여부, 최근 로그인 일시 등을 포함합니다.
                    """
    )
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "status 값 누락 또는 ACTIVE/INACTIVE 외의 값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음 / ADMIN 계정 변경 시도"),
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

    @Operation(
            summary = "사용자 역할 변경",
            description = """
                    피보호자(WARD) ↔ 보호자(GUARDIAN) 역할을 변경합니다.

                    [주의사항]
                    - ADMIN 계정은 역할 변경 불가합니다.
                    - ADMIN으로의 변경은 허용되지 않습니다.
                    - 역할 변경 시 기존 연결 관계(connections)에는 영향을 주지 않습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역할 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "role 값 누락 / ADMIN으로 변경 시도"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음 / ADMIN 계정 역할 변경 시도"),
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

    @Operation(
            summary = "사용자 강제 탈퇴",
            description = """
                    피보호자/보호자 계정을 영구 삭제합니다.

                    [주의사항]
                    - 삭제된 계정은 복구할 수 없습니다.
                    - 해당 사용자의 연결 관계(connections), 게임 결과(game_results), 병원 예약도 함께 삭제됩니다.
                    - ADMIN 계정은 삭제 불가합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "강제 탈퇴 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음 / ADMIN 계정 삭제 시도"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> forceDeleteUser(
            @Parameter(description = "사용자 UUID") @PathVariable String userId) {
        adminService.forceDeleteUser(userId);
        return ApiResponse.ok("사용자가 강제 탈퇴 처리되었습니다.");
    }

    // =============================================
    // 연결 관계 관리
    // =============================================

    @Operation(
            summary = "전체 연결 관계 조회",
            description = """
                    보호자-피보호자 전체 연결 관계를 페이징하여 조회합니다.
                    PENDING(수락 대기) / ACTIVE(연결됨) / CANCELLED(해제됨) 상태 모두 포함됩니다.

                    [기본 정렬]
                    - 연결 요청일 내림차순, 페이지 크기 20

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 20)
                    - sort: 정렬 기준 (예: sort=createdAt,desc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 목록 배열
                    data.totalElements → 전체 항목 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 번호 (0부터)
                    data.size          → 페이지당 크기
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 관계 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/connections")
    public ApiResponse<Page<ConnectionResponse>> getConnections(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getConnections(pageable));
    }

    @Operation(
            summary = "특정 보호자의 연결 목록 조회",
            description = """
                    특정 보호자에 연결된 피보호자 목록을 조회합니다.
                    PENDING / ACTIVE / CANCELLED 상태 모두 포함됩니다.

                    [주의사항]
                    - guardianId는 반드시 GUARDIAN 역할 사용자여야 합니다. WARD UUID 입력 시 400 반환.

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 20)
                    - sort: 정렬 기준 (예: sort=createdAt,desc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 목록 배열
                    data.totalElements → 전체 항목 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 번호 (0부터)
                    data.size          → 페이지당 크기
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "guardianId가 GUARDIAN 역할이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/connections/guardian/{guardianId}")
    public ApiResponse<Page<ConnectionResponse>> getConnectionsByGuardian(
            @Parameter(description = "보호자 UUID (GUARDIAN 역할만 가능)") @PathVariable String guardianId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getConnectionsByGuardian(guardianId, pageable));
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "역할 불일치 (guardianId가 GUARDIAN이 아니거나 wardId가 WARD가 아님) / 비활성화 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 PENDING 또는 ACTIVE 연결이 존재함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/connections")
    public ApiResponse<ConnectionResponse> forceConnect(
            @Valid @RequestBody AdminForceConnectRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminService.forceConnect(request, adminId));
    }

    @Operation(
            summary = "보호자-피보호자 강제 연결 해제",
            description = """
                    관리자가 보호자-피보호자 연결을 강제로 해제(CANCELLED)합니다.
                    해제 후 동일 쌍의 재연결은 POST /api/admin/connections 로 가능합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "연결 해제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 연결 관계 (connectionId 확인 필요)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/connections/{connectionId}")
    public ApiResponse<Void> forceDisconnect(
            @Parameter(description = "연결 ID (ConnectionResponse.id)") @PathVariable Long connectionId) {
        adminService.forceDisconnect(connectionId);
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

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 20)
                    - sort: 정렬 기준 (기본값: detectedAt,desc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 목록 배열
                    data.totalElements → 전체 항목 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 번호 (0부터)
                    data.size          → 페이지당 크기
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이상감지 이벤트 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "guardianId가 GUARDIAN 역할이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자 (guardianId 입력 시)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/anomaly-events")
    public ApiResponse<Page<AnomalyEventResponse>> getAnomalyEvents(
            @Parameter(description = "보호자 UUID (미입력 시 전체 조회, GUARDIAN 역할만 허용)")
            @RequestParam(required = false) String guardianId,
            @Parameter(description = "조회 시작 일시 (ISO 8601, 예: 2025-01-01T00:00:00+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
            @Parameter(description = "조회 종료 일시 (ISO 8601, 예: 2025-12-31T23:59:59+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate,
            @PageableDefault(size = 20, sort = "detectedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getAnomalyEvents(guardianId, startDate, endDate, pageable));
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

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 20)
                    - sort: 정렬 기준 (기본값: playedAt,desc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 목록 배열
                    data.totalElements → 전체 항목 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 번호 (0부터)
                    data.size          → 페이지당 크기
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게임 결과 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "userId가 WARD 역할이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자 (userId 입력 시)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/game-results")
    public ApiResponse<Page<GameResultResponse>> getGameResults(
            @Parameter(description = "피보호자 UUID (미입력 시 전체 조회, WARD 역할만 허용)")
            @RequestParam(required = false) String userId,
            @Parameter(description = "게임 유형 (MATCHING / WORD_QUIZ / ADDITION / SUBTRACTION, 미입력 시 전체)")
            @RequestParam(required = false) GameType gameType,
            @Parameter(description = "조회 시작 일시 (ISO 8601, 예: 2025-01-01T00:00:00+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startDate,
            @Parameter(description = "조회 종료 일시 (ISO 8601, 예: 2025-12-31T23:59:59+09:00)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endDate,
            @PageableDefault(size = 20, sort = "playedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getGameResults(userId, gameType, startDate, endDate, pageable));
    }

    // =============================================
    // 공지 관리
    // =============================================

    @Operation(
            summary = "공지 목록 조회",
            description = """
                    공지 목록을 페이징하여 조회합니다.

                    [필터 조건]
                    - isPublished: true(발행된 공지만) / false(미발행 공지만) / 미입력(전체 조회)

                    [작성자 탈퇴 시]
                    authorName 은 null 로 반환됩니다.

                    [공지 관리 흐름]
                    1. POST /api/admin/announcements           → 공지 작성 (기본 미발행)
                    2. PUT  /api/admin/announcements/{id}      → 내용 수정
                    3. PATCH /api/admin/announcements/{id}/publish → 발행 처리

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 20)
                    - sort: 정렬 기준 (기본값: createdAt,desc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 목록 배열
                    data.totalElements → 전체 항목 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 번호 (0부터)
                    data.size          → 페이지당 크기
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/announcements")
    public ApiResponse<Page<AnnouncementResponse>> getAnnouncements(
            @Parameter(description = "발행 여부 필터 (true: 발행, false: 미발행, 미입력: 전체)")
            @RequestParam(required = false) Boolean isPublished,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getAnnouncements(isPublished, pageable));
    }

    @Operation(
            summary = "공지 상세 조회",
            description = "공지 ID로 단건 상세 조회합니다. 미발행 공지도 조회 가능합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "공지 상세 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/announcements/{id}")
    public ApiResponse<AnnouncementResponse> getAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id) {
        return ApiResponse.ok(adminService.getAnnouncement(id));
    }

    @Operation(
            summary = "공지 작성",
            description = """
                    새 공지를 작성합니다.
                    작성 직후 isPublished=false(미발행) 상태입니다.
                    발행하려면 PATCH /api/admin/announcements/{id}/publish 를 호출하세요.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "작성된 공지 반환 (isPublished=false)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 또는 content 누락 / title 200자 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PostMapping("/announcements")
    public ApiResponse<AnnouncementResponse> createAnnouncement(
            @Valid @RequestBody AnnouncementCreateRequest request,
            @AuthenticationPrincipal String adminId) {
        return ApiResponse.ok(adminService.createAnnouncement(request, adminId));
    }

    @Operation(
            summary = "공지 수정",
            description = """
                    공지의 제목과 내용을 수정합니다.
                    발행 상태(isPublished)는 변경되지 않습니다.
                    발행된 공지도 수정 가능합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정된 공지 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "title 또는 content 누락 / title 200자 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PutMapping("/announcements/{id}")
    public ApiResponse<AnnouncementResponse> updateAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id,
            @Valid @RequestBody AnnouncementUpdateRequest request) {
        return ApiResponse.ok(adminService.updateAnnouncement(id, request));
    }

    @Operation(
            summary = "공지 발행/취소 토글",
            description = """
                    공지의 발행 상태를 토글합니다.
                    - 미발행(false) → 발행(true): publishedAt이 현재 시각으로 설정됩니다.
                    - 발행(true) → 취소(false): publishedAt이 null로 초기화됩니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경된 공지 반환 (isPublished 값 확인)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @PatchMapping("/announcements/{id}/publish")
    public ApiResponse<AnnouncementResponse> togglePublish(
            @Parameter(description = "공지 ID") @PathVariable Long id) {
        return ApiResponse.ok(adminService.togglePublish(id));
    }

    @Operation(
            summary = "공지 삭제",
            description = """
                    공지를 영구 삭제합니다.

                    [주의사항]
                    - 삭제된 공지는 복구할 수 없습니다.
                    - 발행된 공지도 삭제 가능합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 공지"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @DeleteMapping("/announcements/{id}")
    public ApiResponse<Void> deleteAnnouncement(
            @Parameter(description = "공지 ID") @PathVariable Long id) {
        adminService.deleteAnnouncement(id);
        return ApiResponse.ok("공지가 삭제되었습니다.");
    }

    // =============================================
    // 접속 로그 조회
    // =============================================

    @Operation(
            summary = "접속 로그 조회",
            description = """
                    전체 접속 로그를 페이징하여 조회합니다.

                    [포함 이력]
                    - LOGIN: 일반 로그인
                    - KAKAO_LOGIN: 카카오 로그인
                    - LOGOUT: 로그아웃
                    - TOKEN_ISSUE: Access Token 재발급
                    - PASSWORD_RESET: 비밀번호 재설정

                    [기본 정렬]
                    - 발생일 내림차순, 페이지 크기 50

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작, 기본값 0)
                    - size: 페이지당 항목 수 (기본값 50)
                    - sort: 정렬 기준 (기본값: createdAt,desc)

                    [페이지네이션 응답 구조]
                    data.content       → 실제 목록 배열
                    data.totalElements → 전체 항목 수
                    data.totalPages    → 전체 페이지 수
                    data.number        → 현재 페이지 번호 (0부터)
                    data.size          → 페이지당 크기
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "접속 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류")
    })
    @GetMapping("/access-logs")
    public ApiResponse<Page<AccessLogResponse>> getAccessLogs(
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminService.getAccessLogs(pageable));
    }
}
