package kr.silverbridge.main.domain.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.companion.entity.CompanionEndReason;
import kr.silverbridge.main.domain.companion.entity.CompanionMood;
import kr.silverbridge.main.domain.companion.entity.CompanionSession;

/**
 * AI 대화 결과({@code finish} 응답 {@code data})를 저장할 값으로 바꾼 것. 원문 필드는 계약에도 없고 여기에도 없다.
 *
 * <p>믿는 것은 {@code riskFlag}·{@code alertLevel}뿐이다(결정 E) - 그 밖의 판단(주의 등급 등)은 백엔드가 한다.
 * 모르는 종료 이유는 {@code USER_STOP}, 모르는 기분은 null(판단할 수 없음)로 둔다.</p>
 */
record CompanionResult(String dialogueId, CompanionEndReason endReason, CompanionMood mood, int turnCount,
                       Integer answeredCount, Integer noResponseCount, boolean riskFlag, boolean urgent,
                       String promptVersion) {

    private static final int PROMPT_VERSION_MAX = 100;

    static CompanionResult from(JsonNode data) {
        CompanionEndReason endReason = CompanionEndReason.fromAi(text(data, "endReason"))
                .orElse(CompanionEndReason.USER_STOP);
        CompanionMood mood = CompanionMood.fromAi(moodText(data)).orElse(null);
        JsonNode version = data.get("promptVersion");
        String promptVersion = version == null || version.isNull() ? null : version.toString();
        if (promptVersion != null && promptVersion.length() > PROMPT_VERSION_MAX) {
            promptVersion = promptVersion.substring(0, PROMPT_VERSION_MAX);
        }
        return new CompanionResult(
                text(data, "dialogueId"),
                endReason,
                mood,
                Math.max(0, data.path("turnCount").asInt(0)),
                optionalInt(data, "answeredCount"),
                optionalInt(data, "noResponseCount"),
                data.path("riskFlag").asBoolean(false),
                CompanionSession.ALERT_URGENT.equals(text(data, "alertLevel")),
                promptVersion);
    }

    /** 마감 스케줄러가 닫은 대화 - AI는 finish를 USER_STOP으로 기록하지만 실제로는 대답이 끊긴 것이다. */
    CompanionResult asAbandoned() {
        if (endReason != CompanionEndReason.USER_STOP) {
            return this;
        }
        return new CompanionResult(dialogueId, CompanionEndReason.NO_RESPONSE, mood, turnCount, answeredCount,
                noResponseCount, riskFlag, urgent, promptVersion);
    }

    boolean needsRiskAlert() {
        return riskFlag || urgent;
    }

    /** {@code mood}가 비어 있을 때만 {@code moodCode}를 본다. {@code moodKo}(화면용 한글)는 읽지 않는다. */
    private static String moodText(JsonNode data) {
        String mood = text(data, "mood");
        return mood == null || mood.isBlank() ? text(data, "moodCode") : mood;
    }

    private static String text(JsonNode data, String field) {
        JsonNode node = data.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Integer optionalInt(JsonNode data, String field) {
        JsonNode node = data.get(field);
        return node != null && node.isInt() ? Math.max(0, node.asInt()) : null;
    }
}
