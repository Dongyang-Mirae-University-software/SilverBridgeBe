package kr.silverbridge.main.domain.game.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.game.dto.GuardianGameActivityResponse;
import kr.silverbridge.main.domain.game.dto.GuardianGameProgressResponse;
import kr.silverbridge.main.domain.game.service.GuardianGameService;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호자 게임 관리 API - 연결된 피보호자의 치매 예방 게임 기록 (2026-11-19).
 * 클래스 레벨 {@code @PreAuthorize("hasRole('GUARDIAN')")}로 GUARDIAN만 접근 가능(WARD/ADMIN 403).
 */
@Tag(name = "보호자 - 게임")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianGameController {

    private final GuardianGameService guardianGameService;

    @Operation(summary = "피보호자 게임 진행 요약",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자의 게임별 진행 상황과 총점을 반환합니다.
                    한 번도 하지 않은 게임은 progress가 null입니다.
                    시각(lastPlayedAt·startedAt·updatedAt·clearedAt)은 시간대 표기 없는 UTC 문자열입니다.

                    [FE 사용법]
                    게임 기록은 이 API로 받습니다. AI 서버를 직접 부르거나 /api/streams 프록시로 부르지 마세요.
                    게임 사용자 번호는 서버가 wardId로 계산하므로 보내지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "진행 요약 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "wardId 누락", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / GAME_NOT_AUTHORIZED(연결되지 않은 피보호자)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "GAME_UNAVAILABLE(게임 서버 응답 없음)", content = @Content)
    })
    @GetMapping("/api/guardian/game/progress")
    public ResponseEntity<ApiResponse<GuardianGameProgressResponse>> progress(
            @AuthenticationPrincipal String guardianId,
            @RequestParam String wardId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(guardianGameService.getProgress(guardianId, wardId)));
    }

    @Operation(summary = "피보호자 날짜별 게임 활동량",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    ACTIVE 연결된 피보호자의 최근 days일 동안 날짜(KST, YYYY-MM-DD)별 풀이 수·정답 수·점수를 반환합니다.
                    활동이 있는 날만 들어 있습니다. days는 1~366(생략 시 182)이며 범위 밖이면 400 GAME_INVALID_DAYS입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "활동량 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "GAME_INVALID_DAYS(1~366 밖) / wardId 누락 / days가 숫자 아님", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "보호자 권한 필요 / GAME_NOT_AUTHORIZED(연결되지 않은 피보호자)", content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "GAME_UNAVAILABLE(게임 서버 응답 없음)", content = @Content)
    })
    @GetMapping("/api/guardian/game/activity")
    public ResponseEntity<ApiResponse<GuardianGameActivityResponse>> activity(
            @AuthenticationPrincipal String guardianId,
            @RequestParam String wardId,
            @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(guardianGameService.getActivity(guardianId, wardId, days)));
    }
}
