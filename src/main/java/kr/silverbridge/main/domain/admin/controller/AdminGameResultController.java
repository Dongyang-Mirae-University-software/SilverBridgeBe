package kr.silverbridge.main.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.admin.dto.AdminGameResultResponse;
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

@Tag(name = "관리자 - 회원관리")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
public class AdminGameResultController {

    private final AdminService adminService;

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
}
