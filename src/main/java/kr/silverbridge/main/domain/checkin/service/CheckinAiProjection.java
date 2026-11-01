package kr.silverbridge.main.domain.checkin.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.checkin.entity.CheckinEmotion;
import kr.silverbridge.main.domain.checkin.entity.CheckinResult.Evidence;
import kr.silverbridge.main.global.validation.TextSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 응답 → BE 값. <b>허용 목록</b>으로 거른다 - AI가 새 필드를 더해도 자동으로 빠지고, 모르는 값은 null(모름)로 본다.
 * 생활 신호는 한글 값을 영문 코드로 바꾼다(허용값은 V65 CHECK와 같다).
 */
final class CheckinAiProjection {

    static final int MAX_TEXT = 1000;
    static final int MAX_SUMMARY = 500;
    static final int MAX_EVIDENCE = 20;

    static final Map<String, Map<String, String>> SIGNAL_CODES = Map.of(
            "sleep", Map.of("좋음", "GOOD", "나쁨", "BAD"),
            "meal", Map.of("먹음", "ATE", "거름", "SKIPPED"),
            "pain", Map.of("있음", "YES", "없음", "NO"),
            "mood", Map.of("좋음", "GOOD", "보통", "NORMAL", "나쁨", "BAD"),
            "lonely", Map.of("있음", "YES", "없음", "NO"));

    private CheckinAiProjection() {
    }

    record Turn(String transcript, String reply, boolean done, boolean emergency, boolean selfHarm) {
    }

    record Finish(CheckinEmotion emotion, Map<String, String> signals, boolean emergency, boolean selfHarm,
                  String summary, List<Evidence> evidence, int turns, Double avgAnswerSec, Double avgSpeechSec,
                  Double avgPauseRatio, Double avgCharsPerSec, Double avgLoudnessDb) {

        String signal(String name) {
            return signals.get(name);
        }
    }

    /** 답 문장이 없으면 null(호출부가 503) - 읽어 드릴 말이 없으면 대화를 이어갈 수 없다. */
    static Turn turn(JsonNode data) {
        String reply = text(data.path("reply"), MAX_TEXT);
        if (reply == null || reply.isEmpty()) {
            return null;
        }
        JsonNode risk = data.path("risk");
        return new Turn(text(data.path("transcript"), MAX_TEXT), reply, data.path("done").asBoolean(false),
                risk.path("emergency").asBoolean(false), risk.path("selfHarm").asBoolean(false));
    }

    static Finish finish(JsonNode data) {
        CheckinEmotion emotion = data.path("emotion").isTextual()
                ? CheckinEmotion.fromAiValue(data.path("emotion").asText()).orElse(CheckinEmotion.UNDECIDED)
                : CheckinEmotion.UNDECIDED;

        Map<String, String> signals = new java.util.HashMap<>();
        JsonNode rawSignals = data.path("signals");
        SIGNAL_CODES.forEach((name, codes) -> {
            JsonNode value = rawSignals.path(name);
            if (value.isTextual() && codes.containsKey(value.asText())) {
                signals.put(name, codes.get(value.asText()));
            }
        });

        List<Evidence> evidence = new ArrayList<>();
        JsonNode rawEvidence = data.path("evidence");
        if (rawEvidence.isArray()) {
            for (JsonNode item : rawEvidence) {
                if (evidence.size() >= MAX_EVIDENCE) {
                    break;
                }
                String signal = item.path("signal").asText(null);
                JsonNode turnNo = item.path("turnNo");
                if (signal != null && SIGNAL_CODES.containsKey(signal) && turnNo.canConvertToInt()
                        && turnNo.asInt() >= 1 && turnNo.asInt() <= 20) {
                    evidence.add(new Evidence(signal, turnNo.asInt()));
                }
            }
        }

        JsonNode risk = data.path("risk");
        JsonNode voice = data.path("voice");
        JsonNode turns = data.path("turns");
        return new Finish(emotion, Map.copyOf(signals), risk.path("emergency").asBoolean(false),
                risk.path("selfHarm").asBoolean(false), text(data.path("summary"), MAX_SUMMARY), List.copyOf(evidence),
                turns.canConvertToInt() ? Math.max(0, Math.min(turns.asInt(), 20)) : 0,
                number(voice.path("avgAnswerSec"), 0, 600), number(voice.path("avgSpeechSec"), 0, 600),
                number(voice.path("avgPauseRatio"), 0, 1), number(voice.path("avgCharsPerSec"), 0, 100),
                number(voice.path("avgLoudnessDb"), -200, 200));
    }

    /** 문자열만, 제어문자 제거, 상한 자르기. 문자열이 아니면 null. */
    private static String text(JsonNode node, int max) {
        if (!node.isTextual()) {
            return null;
        }
        String value = TextSanitizer.sanitizeMultiline(node.asText());
        if (value == null) {
            return null;
        }
        return value.codePointCount(0, value.length()) <= max
                ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** 유한한 숫자이고 범위 안이면 그 값, 아니면 null(모르는 값을 0으로 채우지 않는다). */
    private static Double number(JsonNode node, double min, double max) {
        if (!node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        return Double.isFinite(value) && value >= min && value <= max ? value : null;
    }
}
