package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AI finish 응답의 기분 필드(mood·moodCode·moodKo) 해석. */
class CompanionResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("새 AI finish 응답(mood=WORRY, moodKo=걱정)에서 WORRY를 얻는다")
    void englishMoodFromNewAi() throws Exception {
        CompanionResult result = CompanionResult.from(json("""
                {"dialogueId":"d-1","endReason":"최대 턴 수","mood":"WORRY","moodCode":"WORRY","moodKo":"걱정",
                 "turnCount":8,"riskFlag":false,"alertLevel":"NONE"}
                """));

        assertThat(result.mood()).isEqualTo(CompanionMood.WORRY);
    }

    @Test
    @DisplayName("옛 AI 한글 mood도 그대로 받는다(배포 순서가 바뀌어도 안전)")
    void koreanMoodFromOldAi() throws Exception {
        assertThat(CompanionResult.from(json("{\"mood\":\"우울\"}")).mood()).isEqualTo(CompanionMood.DEPRESSED);
    }

    @Test
    @DisplayName("mood가 없거나 비면 moodCode를 본다")
    void fallsBackToMoodCode() throws Exception {
        assertThat(CompanionResult.from(json("{\"moodCode\":\"CALM\"}")).mood()).isEqualTo(CompanionMood.CALM);
        assertThat(CompanionResult.from(json("{\"mood\":null,\"moodCode\":\"JOY\"}")).mood())
                .isEqualTo(CompanionMood.JOY);
        assertThat(CompanionResult.from(json("{\"mood\":\"\",\"moodCode\":\"WORRY\"}")).mood())
                .isEqualTo(CompanionMood.WORRY);
    }

    @Test
    @DisplayName("UNKNOWN은 null(판단 불가)이고 moodKo만 있으면 읽지 않는다")
    void unknownAndMoodKoOnlyAreNull() throws Exception {
        assertThat(CompanionResult.from(json("{\"mood\":\"UNKNOWN\",\"moodCode\":\"UNKNOWN\",\"moodKo\":null}")).mood())
                .isNull();
        assertThat(CompanionResult.from(json("{\"moodKo\":\"걱정\"}")).mood()).isNull();
        assertThat(CompanionResult.from(json("{}")).mood()).isNull();
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }
}
