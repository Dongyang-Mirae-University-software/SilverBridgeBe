package kr.silverbridge.main.domain.game.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.silverbridge.main.domain.game.dto.GameRankingResponse;
import kr.silverbridge.main.domain.game.dto.GameResultRequest;
import kr.silverbridge.main.domain.game.dto.GameResultResponse;
import kr.silverbridge.main.domain.game.service.GameService;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "피보호자")
@RestController
@RequestMapping("/api/ward/game")
@RequiredArgsConstructor
@PreAuthorize("hasRole('WARD')")
public class WardGameController {

    private final GameService gameService;

    @Operation(summary = "게임 결과 저장",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    게임 완료 후 결과를 저장합니다.
                    저장 후 자동으로 성능 저하 여부를 감지합니다.

                    [성능 저하 감지 기준]
                    - 최근 5회 평균 난이도가 이전 5회 대비 1.0 이상 하락
                    - AND 최근 5회 클리어율 40% 미만
                    → 조건 충족 시 연결된 모든 보호자에게 FCM 알림 전송
                    """)
    @PostMapping("/results")
    public ResponseEntity<ApiResponse<GameResultResponse>> saveResult(
            @AuthenticationPrincipal String wardId,
            @Valid @RequestBody GameResultRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.saveResult(wardId, request)));
    }

    @Operation(summary = "내 게임 기록 조회",
            description = """
                    [요청 헤더]
                    Authorization: Bearer {accessToken}

                    [페이지네이션 쿼리 파라미터]
                    - page: 페이지 번호 (0부터 시작)
                    - size: 페이지당 항목 수 (최대 100)
                    """)
    @GetMapping("/results")
    public ResponseEntity<ApiResponse<Page<GameResultResponse>>> getMyResults(
            @AuthenticationPrincipal String wardId,
            @PageableDefault(sort = "playedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getMyResults(wardId, pageable)));
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
    @GetMapping("/ranking")
    public ResponseEntity<ApiResponse<Page<GameRankingResponse>>> getRanking(
            @RequestParam GameType gameType,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getRanking(gameType, pageable)));
    }
}
