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
 * 문자열만, 제어문자 제거·공백 정리, 1~30자, 중복 제외, 최대 4개. 배열이 아니면 칸을 지운다(답은 그대로 내린다).
 * 내용은 로그에 남기지 않는다(불변 규칙 ③).</p>
 *
 * <p>칩 종류 {@code suggestedKind}(2026-11-02, AI 되묻기 답 칩): 값이 {@code "answer"}이고 정리한 칩이 남을 때만 둔다.
 * 그 밖의 값·칩이 없는 답은 칸을 지운다 - FE는 이 값으로 패널 제목만 바꾸므로 모르는 값을 내리지 않는다.
 * 상한 4는 답 칩(고정 4개) 때문이다. AI가 만드는 이어서 물어볼 질문은 AI에서 3개까지다.</p>
 */
final class SuggestedQuestions {

    static final String FIELD = "suggestedQuestions";
    static final String KIND_FIELD = "suggestedKind";
    static final String KIND_ANSWER = "answer";
    static final int MAX_COUNT = 4;
    static final int MAX_CHARS = 30;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private SuggestedQuestions() {
    }

    /** {@code reply}가 객체면 그 안의 칸을 제자리에서 정리한다. */
    static void sanitize(JsonNode reply) {
        if (!(reply instanceof ObjectNode object)) {
            return;
        }
        if (!object.has(FIELD)) {
            object.remove(KIND_FIELD);
            return;
        }
        JsonNode raw = object.get(FIELD);
        if (!raw.isArray()) {
            object.remove(FIELD);
            object.remove(KIND_FIELD);
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
        JsonNode kind = object.get(KIND_FIELD);
        if (kind != null && !(kind.isTextual() && KIND_ANSWER.equals(kind.asText()) && !kept.isEmpty())) {
            object.remove(KIND_FIELD);
        }
    }
}
