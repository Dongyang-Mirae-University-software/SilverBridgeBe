package kr.silverbridge.main.domain.game.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.silverbridge.main.domain.game.dto.GameRankingResponse;
import kr.silverbridge.main.domain.game.dto.GameResultResponse;
import kr.silverbridge.main.domain.game.service.GameService;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "보호자")
@RestController
@RequestMapping("/api/guardian")
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUARDIAN')")
public class GuardianGameController {

    private final GameService gameService;

    @Operation(summary = "피보호자 게임 기록 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    연결된 피보호자의 게임 기록만 조회 가능합니다.
                    플레이 일시 내림차순으로 반환됩니다.
                    """)
    @GetMapping("/game-results/{wardId}")
    public ResponseEntity<ApiResponse<List<GameResultResponse>>> getWardResults(
            @AuthenticationPrincipal String guardianId,
            @PathVariable String wardId) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getWardResults(guardianId, wardId)));
    }

    @Operation(summary = "전체 랭킹 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    피보호자 전체를 대상으로 한 게임 유형별 랭킹입니다.
                    평균 점수 내림차순으로 정렬됩니다.

                    [쿼리 파라미터]
                    - gameType: MATCHING, WORD_QUIZ, ADDITION, SUBTRACTION
                    """)
    @GetMapping("/game/ranking")
    public ResponseEntity<ApiResponse<List<GameRankingResponse>>> getRanking(
            @RequestParam GameType gameType) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getRanking(gameType)));
    }
}
