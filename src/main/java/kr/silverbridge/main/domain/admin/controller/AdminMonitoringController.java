package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AccessLogResponse;
import kr.silverbridge.main.domain.admin.dto.AdminAuditLogResponse;
import kr.silverbridge.main.domain.admin.dto.AdminGameResultResponse;
import kr.silverbridge.main.domain.admin.dto.AnomalyEventResponse;
import kr.silverbridge.main.domain.admin.service.AdminAuditLogService;
import kr.silverbridge.main.domain.admin.service.AdminService;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@Tag(name = "관리자 - 모니터링/감사")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminMonitoringController {

    private final AdminService adminService;
    private final AdminAuditLogService auditLogService;

    @Operation(summary = "이상감지 이벤트 조회",
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
                    """)
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

    @Operation(summary = "게임 결과 조회",
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
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "게임 결과 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "userId가 WARD 역할이 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 사용자 (userId 입력 시)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/game/result/select")
    public ApiResponse<List<AdminGameResultResponse>> getGameResults(
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

    @Operation(summary = "사용자 접근 로그 조회",
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
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "접속 로그 목록 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "서버 내부 오류", content = @Content)
    })
    @GetMapping("/accesslog/select")
    public ApiResponse<List<AccessLogResponse>> getAccessLogs() {
        return ApiResponse.ok(adminService.getAccessLogs());
    }

    @Operation(summary = "관리자 행동 감사 로그 조회",
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
                    """)
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
