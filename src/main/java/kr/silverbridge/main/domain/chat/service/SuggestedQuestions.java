package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 챗 전송 응답의 {@code suggestedQuestions}(이어서 물어볼 질문, AI #135, 2026-10-30)를 FE에 내리기 전에 정리한다.
 *
 * <p>전송 응답은 AI 값을 그대로 넘기지만 이 칸은 FE가 버튼으로 그대로 그리므로 형식을 보장한다:
 * 문자열만, 제어문자 제거·공백 정리, 1~30자, 중복 제외, 최대 3개. 배열이 아니면 칸을 지운다(답은 그대로 내린다).
 * 내용은 로그에 남기지 않는다(불변 규칙 ③).</p>
 */
final class SuggestedQuestions {

    static final String FIELD = "suggestedQuestions";
    static final int MAX_COUNT = 3;
    static final int MAX_CHARS = 30;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private SuggestedQuestions() {
    }

    /** {@code reply}가 객체면 그 안의 칸을 제자리에서 정리한다. */
    static void sanitize(JsonNode reply) {
        if (!(reply instanceof ObjectNode object) || !object.has(FIELD)) {
            return;
        }
        JsonNode raw = object.get(FIELD);
        if (!raw.isArray()) {
            object.remove(FIELD);
            return;
        }
        Set<String> kept = new LinkedHashSet<>();
        for (JsonNode item : raw) {
            if (!item.isTextual()) {
                continue;
            }
            String text = CONTROL.matcher(item.asText()).replaceAll(" ").trim().replaceAll("\\s+", " ");
            if (!text.isEmpty() && text.length() <= MAX_CHARS) {
                kept.add(text);
            }
            if (kept.size() == MAX_COUNT) {
                break;
            }
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        kept.forEach(out::add);
        object.set(FIELD, out);
    }
}
