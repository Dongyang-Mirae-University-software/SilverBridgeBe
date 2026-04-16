package kr.silverbridge.main.domain.game.service;

import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.connection.repository.ConnectionRepository;
import kr.silverbridge.main.domain.game.dto.GameRankingResponse;
import kr.silverbridge.main.domain.game.dto.GameResultRequest;
import kr.silverbridge.main.domain.game.dto.GameResultResponse;
import kr.silverbridge.main.domain.game.entity.GameResult;
import kr.silverbridge.main.domain.game.repository.GameResultRepository;
import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.ConnectionStatus;
import kr.silverbridge.main.global.enums.GameType;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    // 성능 저하 감지 기준 (치매 예방 관점)
    // 최근 5회 평균 난이도 - 이전 5회 평균 난이도 <= -1.0 (난이도가 하락)
    // AND 최근 5회 클리어율 < 40%
    private static final int    PERFORMANCE_CHECK_COUNT = 5;
    private static final double DIFFICULTY_DROP_THRESHOLD = 1.0;  // 난이도 하락 기준
    private static final double CLEAR_RATE_THRESHOLD      = 0.40; // 클리어율 기준 (40%)

    private final GameResultRepository gameResultRepository;
    private final ConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final FcmService fcmService;

    // ─── 피보호자: 게임 결과 저장 ────────────────────────────────

    @Transactional
    public GameResultResponse saveResult(String wardId, GameResultRequest request) {
        GameResult result = GameResult.builder()
                .userId(wardId)
                .gameType(request.getGameType())
                .difficulty(request.getDifficulty())
                .isCleared(request.getIsCleared())
                .score(request.getScore())
                .durationSeconds(request.getDurationSeconds())
                .build();

        gameResultRepository.save(result);

        // 저장 후 성능 저하 감지 (비동기적으로 처리 — 여기선 동기)
        checkPerformanceDecline(wardId);

        return GameResultResponse.from(result);
    }

    // ─── 피보호자: 내 게임 기록 조회 ─────────────────────────────

    @Transactional(readOnly = true)
    public Page<GameResultResponse> getMyResults(String wardId, Pageable pageable) {
        return gameResultRepository.findByUserIdOrderByPlayedAtDesc(wardId, pageable)
                .map(GameResultResponse::from);
    }

    // ─── 보호자: 피보호자 게임 기록 조회 ─────────────────────────

    @Transactional(readOnly = true)
    public Page<GameResultResponse> getWardResults(String guardianId, String wardId, Pageable pageable) {
        // 연결 관계 검증
        validateGuardianWardRelation(guardianId, wardId);
        return gameResultRepository.findByUserIdOrderByPlayedAtDesc(wardId, pageable)
                .map(GameResultResponse::from);
    }

    // ─── 게임 랭킹 (피보호자 전체) ───────────────────────────────

    @Transactional(readOnly = true)
    public Page<GameRankingResponse> getRanking(GameType gameType, Pageable pageable) {
        Page<Object[]> raw = gameResultRepository.findRankingByGameType(gameType, pageable);

        // userId → 사용자 이름 일괄 조회 (N+1 방지)
        List<String> userIds = raw.stream()
                .map(row -> (String) row[0])
                .toList();
        Map<String, String> nameMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        int offset = (int) pageable.getOffset();
        List<GameRankingResponse> content = new ArrayList<>();
        for (int i = 0; i < raw.getContent().size(); i++) {
            Object[] row = raw.getContent().get(i);
            String userId = (String) row[0];
            content.add(new GameRankingResponse(
                    offset + i + 1,
                    userId,
                    nameMap.getOrDefault(userId, "알 수 없음"),
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).doubleValue()
            ));
        }
        return new PageImpl<>(content, pageable, raw.getTotalElements());
    }

    // ─── 성능 저하 감지 → 보호자 알림 ───────────────────────────

    // 최근 5회 vs 이전 5회 비교:
    // 난이도 평균이 1.0 이상 떨어지고 최근 5회 클리어율이 40% 미만이면 경고
    private void checkPerformanceDecline(String wardId) {
        List<GameResult> recent = gameResultRepository.findRecentByUserId(
                wardId, PageRequest.of(0, PERFORMANCE_CHECK_COUNT * 2));

        if (recent.size() < PERFORMANCE_CHECK_COUNT * 2) {
            return; // 데이터 부족 — 감지 불가
        }

        List<GameResult> latestFive   = recent.subList(0, PERFORMANCE_CHECK_COUNT);
        List<GameResult> previousFive = recent.subList(PERFORMANCE_CHECK_COUNT, PERFORMANCE_CHECK_COUNT * 2);

        double recentAvgDifficulty   = latestFive.stream().mapToInt(GameResult::getDifficulty).average().orElse(0);
        double previousAvgDifficulty = previousFive.stream().mapToInt(GameResult::getDifficulty).average().orElse(0);
        long   recentClearedCount    = latestFive.stream().filter(GameResult::isCleared).count();
        double recentClearRate       = (double) recentClearedCount / PERFORMANCE_CHECK_COUNT;

        boolean difficultyDropped = (previousAvgDifficulty - recentAvgDifficulty) >= DIFFICULTY_DROP_THRESHOLD;
        boolean clearRateLow      = recentClearRate < CLEAR_RATE_THRESHOLD;

        if (difficultyDropped && clearRateLow) {
            notifyGuardiansOfPerformanceDecline(wardId, recentAvgDifficulty, recentClearRate);
        }
    }

    private void notifyGuardiansOfPerformanceDecline(String wardId, double avgDifficulty, double clearRate) {
        List<Connection> guardians = connectionRepository
                .findByWardIdAndStatusOrderByPriorityAsc(wardId, ConnectionStatus.ACTIVE);
        if (guardians.isEmpty()) return;

        User ward = userRepository.findById(wardId).orElse(null);
        if (ward == null) return;

        List<String> guardianIds = guardians.stream().map(Connection::getGuardianId).toList();
        String clearRateStr = String.format("%.0f", clearRate * 100);

        fcmService.sendToUsers(guardianIds,
                ward.getName() + " 님 게임 성능 저하 감지",
                String.format("최근 클리어율 %s%% — 인지 기능 변화가 감지되었습니다.", clearRateStr),
                Map.of(
                        "type", "PERFORMANCE_DECLINE",
                        "wardId", wardId,
                        "wardName", ward.getName(),
                        "clearRate", clearRateStr
                ));

        log.info("게임 성능 저하 감지 알림: wardId={}, 최근 클리어율={:.0f}%", wardId, clearRate * 100);
    }

    // ─── 내부 헬퍼 ───────────────────────────────────────────────

    private void validateGuardianWardRelation(String guardianId, String wardId) {
        boolean connected = connectionRepository.existsByGuardianIdAndWardIdAndStatusNot(
                guardianId, wardId, ConnectionStatus.CANCELLED);
        if (!connected) {
            throw new CustomException(ErrorCode.CONNECTION_NOT_FOUND);
        }
    }
}
