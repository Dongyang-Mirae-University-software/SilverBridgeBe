package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestedQuestionsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("문자열만 남기고 제어문자·공백을 정리하며 30자 초과·중복을 버리고 최대 4개")
    void 형식_정리() throws Exception {
        JsonNode reply = objectMapper.readTree("""
                {"reply":"ok","suggestedQuestions":[
                  "병원에 가야 할까요?", 3, null, "줄\\n바꿈   질문", "   ",
                  "이 질문은 서른 글자를 넘기도록 아주아주아주아주아주아주 길게 썼어요",
                  "병원에 가야 할까요?", "집에서 뭘 하면 좋아요?", "네 번째 질문", "다섯 번째 질문"]}""");

        SuggestedQuestions.sanitize(reply);

        assertThat(reply.get("suggestedQuestions")).extracting(JsonNode::asText)
                .containsExactly("병원에 가야 할까요?", "줄 바꿈 질문", "집에서 뭘 하면 좋아요?", "네 번째 질문");
        assertThat(reply.get("reply").asText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("배열이 아니면 칸을 지우고 답은 그대로 둔다")
    void 배열_아니면_제거() throws Exception {
        JsonNode reply = objectMapper.readTree("{\"reply\":\"ok\",\"suggestedQuestions\":\"병원에 가야 할까요?\"}");

        SuggestedQuestions.sanitize(reply);

        assertThat(reply.has("suggestedQuestions")).isFalse();
        assertThat(reply.get("reply").asText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("칸이 없는 옛 AI 응답·객체가 아닌 응답은 손대지 않는다")
    void 칸_없으면_그대로() throws Exception {
        JsonNode old = objectMapper.readTree("{\"reply\":\"ok\"}");
        SuggestedQuestions.sanitize(old);
        assertThat(old.toString()).isEqualTo("{\"reply\":\"ok\"}");

        JsonNode array = objectMapper.readTree("[1,2]");
        SuggestedQuestions.sanitize(array);
        assertThat(array.toString()).isEqualTo("[1,2]");
        SuggestedQuestions.sanitize(null);
    }

    @Test
    @DisplayName("칩 종류는 answer 이고 칩이 남을 때만 둔다(되묻기 답 칩, 2026-11-02)")
    void 칩_종류() throws Exception {
        JsonNode answer = objectMapper.readTree("""
                {"reply":"ok","suggestedKind":"answer","suggestedQuestions":["오늘부터 아파요","며칠째 계속 아파요","점점 심해져요","열도 나요"]}""");
        SuggestedQuestions.sanitize(answer);
        assertThat(answer.get("suggestedKind").asText()).isEqualTo("answer");
        assertThat(answer.get("suggestedQuestions")).hasSize(4);

        for (String body : new String[] {
                "{\"reply\":\"ok\",\"suggestedKind\":\"<b>x</b>\",\"suggestedQuestions\":[\"질문\"]}", // 모르는 값
                "{\"reply\":\"ok\",\"suggestedKind\":[\"answer\"],\"suggestedQuestions\":[\"질문\"]}", // 문자열 아님
                "{\"reply\":\"ok\",\"suggestedKind\":\"answer\",\"suggestedQuestions\":[3, \"  \"]}", // 칩이 하나도 안 남음
                "{\"reply\":\"ok\",\"suggestedKind\":\"answer\",\"suggestedQuestions\":\"질문\"}", // 배열 아님
                "{\"reply\":\"ok\",\"suggestedKind\":\"answer\"}"}) { // 칩 칸 없음
            JsonNode reply = objectMapper.readTree(body);
            SuggestedQuestions.sanitize(reply);
            assertThat(reply.has("suggestedKind")).as(body).isFalse();
            assertThat(reply.get("reply").asText()).isEqualTo("ok");
        }
    }
}
