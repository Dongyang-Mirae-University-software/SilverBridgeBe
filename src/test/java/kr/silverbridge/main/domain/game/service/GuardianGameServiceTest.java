package kr.silverbridge.main.domain.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.game.client.AiGameClient;
import kr.silverbridge.main.domain.game.dto.GuardianGameActivityResponse;
import kr.silverbridge.main.domain.game.dto.GuardianGameProgressResponse;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuardianGameServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GUARDIAN = "GRD001";
    private static final String WARD = "aB3x9Z";
    private static final long WARD_GAME_ID = 1455350745L;

    @Mock private ConnectionService connectionService;
    @Mock private AiGameClient aiGameClient;
    @InjectMocks private GuardianGameService service;

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Test
    @DisplayName("연결된 피보호자: wardId 로 계산한 게임 번호로 AI 를 부르고, 허용 필드만 옮긴다(state·게임 번호 제외, 시각은 원문 그대로)")
    void progressConnected() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(aiGameClient.fetchProgress(WARD_GAME_ID)).thenReturn(json("""
                {"userId":1455350745,"totalScore":120,"totalAttempts":9,"lastPlayedAt":"2026-11-18T05:03:09.123456",
                 "games":[
                   {"game":{"slug":"maze","title":"미로","description":"d","totalStages":10,"themeColor":"#fff"},
                    "progress":{"userId":1455350745,"gameSlug":"maze","currentStageNo":3,"score":120,"attempts":9,
                                "cleared":false,"lastAnswerCorrect":true,"state":{"answer":"secret"},
                                "startedAt":"2026-11-01T00:00:00","updatedAt":"2026-11-18T05:03:09.123456","clearedAt":null}},
                   {"game":{"slug":"cards","title":"카드","description":"d2","totalStages":5,"themeColor":"#000"},"progress":null}
                 ]}"""));

        GuardianGameProgressResponse res = service.getProgress(GUARDIAN, WARD);

        assertThat(res.wardId()).isEqualTo(WARD);
        assertThat(res.totalScore()).isEqualTo(120);
        assertThat(res.totalAttempts()).isEqualTo(9);
        assertThat(res.lastPlayedAt()).isEqualTo("2026-11-18T05:03:09.123456");
        assertThat(res.games()).hasSize(2);
        GuardianGameProgressResponse.Progress maze = res.games().get(0).progress();
        assertThat(maze.gameSlug()).isEqualTo("maze");
        assertThat(maze.currentStageNo()).isEqualTo(3);
        assertThat(maze.lastAnswerCorrect()).isTrue();
        assertThat(maze.clearedAt()).isNull();
        assertThat(res.games().get(0).game().totalStages()).isEqualTo(10);
        assertThat(res.games().get(1).progress()).isNull();
    }

    @Test
    @DisplayName("ACTIVE 연결이 아니면(PENDING·해제·미연결 모두 false) 403 GAME_NOT_AUTHORIZED, AI 미호출")
    void notConnected() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(false);

        assertError(() -> service.getProgress(GUARDIAN, WARD), ErrorCode.GAME_NOT_AUTHORIZED);
        assertError(() -> service.getActivity(GUARDIAN, WARD, null), ErrorCode.GAME_NOT_AUTHORIZED);
        verifyNoInteractions(aiGameClient);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc1234", "ab-12", "ab\nX", "한글"})
    @DisplayName("형식이 틀린 wardId 는 DB·AI 조회 없이 403")
    void malformedWardId(String wardId) {
        assertError(() -> service.getProgress(GUARDIAN, wardId), ErrorCode.GAME_NOT_AUTHORIZED);
        verify(connectionService, never()).isActiveConnection(anyString(), anyString());
        verifyNoInteractions(aiGameClient);
    }

    @Test
    @DisplayName("활동: days 생략 시 182, 날짜 없는 항목은 버린다")
    void activityDefaultDays() throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(aiGameClient.fetchActivity(WARD_GAME_ID, 182)).thenReturn(json("""
                {"userId":1455350745,"days":182,"activity":[
                  {"date":"2026-11-17","attempts":4,"correct":3,"score":30},
                  {"attempts":1}]}"""));

        GuardianGameActivityResponse res = service.getActivity(GUARDIAN, WARD, null);

        assertThat(res.days()).isEqualTo(182);
        assertThat(res.activity()).containsExactly(new GuardianGameActivityResponse.Day("2026-11-17", 4, 3, 30));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 366})
    @DisplayName("활동: days 경계 1·366 허용")
    void daysBoundaryAllowed(int days) throws Exception {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(aiGameClient.fetchActivity(WARD_GAME_ID, days)).thenReturn(json("{\"activity\":[]}"));

        assertThat(service.getActivity(GUARDIAN, WARD, days).days()).isEqualTo(days);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 367})
    @DisplayName("활동: days 범위 밖이면 400 GAME_INVALID_DAYS, 연결·AI 조회 없음")
    void daysOutOfRange(int days) {
        assertError(() -> service.getActivity(GUARDIAN, WARD, days), ErrorCode.GAME_INVALID_DAYS);
        verifyNoInteractions(connectionService);
        verify(aiGameClient, never()).fetchActivity(anyLong(), anyInt());
    }

    @Test
    @DisplayName("AI 장애(클라이언트의 503)는 그대로 전달된다")
    void aiUnavailable() {
        when(connectionService.isActiveConnection(GUARDIAN, WARD)).thenReturn(true);
        when(aiGameClient.fetchProgress(WARD_GAME_ID)).thenThrow(new CustomException(ErrorCode.GAME_UNAVAILABLE));

        assertError(() -> service.getProgress(GUARDIAN, WARD), ErrorCode.GAME_UNAVAILABLE);
    }
}
