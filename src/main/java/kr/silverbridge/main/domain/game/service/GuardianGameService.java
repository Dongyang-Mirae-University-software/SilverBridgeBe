package kr.silverbridge.main.domain.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.game.client.AiGameClient;
import kr.silverbridge.main.domain.game.dto.GuardianGameActivityResponse;
import kr.silverbridge.main.domain.game.dto.GuardianGameProgressResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.util.UserIdFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 보호자 게임 관리 - 피보호자 게임 기록을 인가 후 AI에서 읽어 온다 (2026-11-19).
 *
 * <p>인가: 형식이 틀린 wardId는 DB 조회 없이 403(로그엔 길이만), ACTIVE 연결이 아니면 403 + {@code [IDOR-ATTEMPT]}.
 * 두 경우 모두 AI를 부르지 않는다. 인가 근거는 {@link ConnectionService#isActiveConnection}만 쓴다({@code getMyWards} 금지).
 * 게임 사용자 번호는 wardId로 여기서 계산한다 - 클라이언트에서 받지 않는다.</p>
 *
 * <p>D-1(지금 연결 이후 기록만)은 적용하지 않는다(2026-11-19 사용자 결정): 게임 기록은 사건 이력이 아니라 누적 진행 상황이고
 * AI에 날짜 하한 조회가 없다. 다시 연결한 보호자는 연결 이전의 누적 점수·활동도 본다(수용).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianGameService {

    public static final int DEFAULT_DAYS = 182;
    /** AI가 자르는 상한과 같다(AI {@code list_activity_for_user}: 1~366). */
    public static final int MAX_DAYS = 366;

    private final ConnectionService connectionService;
    private final AiGameClient aiGameClient;
    private final GameRelaySlots slots;

    public GuardianGameProgressResponse getProgress(String guardianId, String wardId) {
        requireConnected(guardianId, wardId);
        JsonNode data;
        // 자리는 인가 뒤에 잡고 AI 호출이 끝나면(예외 포함) 돌려준다 - 인가 실패는 자리를 쓰지 않는다(최종 점검 M-4)
        try (GameRelaySlots.Slot ignored = slots.acquire(guardianId)) {
            data = aiGameClient.fetchProgress(GameUserIds.of(wardId));
        }

        List<GuardianGameProgressResponse.GameEntry> games = new ArrayList<>();
        for (JsonNode entry : data.path("games")) {
            JsonNode game = entry.path("game");
            if (!game.isObject()) {
                continue;
            }
            JsonNode progress = entry.path("progress");
            games.add(new GuardianGameProgressResponse.GameEntry(
                    new GuardianGameProgressResponse.Catalog(
                            text(game, "slug"), text(game, "title"), text(game, "description"),
                            intOrNull(game, "totalStages"), text(game, "themeColor")),
                    progress.isObject() ? new GuardianGameProgressResponse.Progress(
                            text(progress, "gameSlug"),
                            intOrNull(progress, "currentStageNo"),
                            longOrNull(progress, "score"),
                            longOrNull(progress, "attempts"),
                            boolOrNull(progress, "cleared"),
                            boolOrNull(progress, "lastAnswerCorrect"),
                            text(progress, "startedAt"),
                            text(progress, "updatedAt"),
                            text(progress, "clearedAt")) : null));
        }
        return new GuardianGameProgressResponse(
                wardId,
                data.path("totalScore").asLong(0),
                data.path("totalAttempts").asLong(0),
                text(data, "lastPlayedAt"),
                games);
    }

    public GuardianGameActivityResponse getActivity(String guardianId, String wardId, Integer days) {
        int range = days == null ? DEFAULT_DAYS : days;
        if (range < 1 || range > MAX_DAYS) {
            throw new CustomException(ErrorCode.GAME_INVALID_DAYS);
        }
        requireConnected(guardianId, wardId);
        JsonNode data;
        try (GameRelaySlots.Slot ignored = slots.acquire(guardianId)) {
            data = aiGameClient.fetchActivity(GameUserIds.of(wardId), range);
        }

        List<GuardianGameActivityResponse.Day> activity = new ArrayList<>();
        for (JsonNode day : data.path("activity")) {
            String date = text(day, "date");
            if (date == null) {
                continue;
            }
            activity.add(new GuardianGameActivityResponse.Day(date,
                    day.path("attempts").asLong(0), day.path("correct").asLong(0), day.path("score").asLong(0)));
        }
        return new GuardianGameActivityResponse(wardId, range, activity);
    }

    private void requireConnected(String guardianId, String wardId) {
        if (!UserIdFormat.isWellFormed(wardId)) {
            log.warn("[IDOR-ATTEMPT] 형식이 맞지 않는 피보호자 ID로 게임 기록 조회 시도: guardianId={}, wardIdLength={}",
                    guardianId, wardId == null ? 0 : wardId.length());
            throw new CustomException(ErrorCode.GAME_NOT_AUTHORIZED);
        }
        if (!connectionService.isActiveConnection(guardianId, wardId)) {
            log.warn("[IDOR-ATTEMPT] 연결되지 않은 피보호자 게임 기록 조회 시도: guardianId={}, wardId={}", guardianId, wardId);
            throw new CustomException(ErrorCode.GAME_NOT_AUTHORIZED);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isContainerNode() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }
}
