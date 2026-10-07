package kr.silverbridge.main.domain.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * AI 상담 기록 응답을 <b>허용 목록(allowlist)</b>으로 걸러 FE에 내린다(2026-10-07 점검 M-2).
 *
 * <p>AI 기록에는 FE가 쓰지 않는 내부 정보가 함께 온다 - {@code contextJson}(요청 때 보낸 프로필 사본 {@code userContext}
 * 포함)·{@code upstreamMeta}·{@code decisionTrace}·{@code userId}. 목록에 없는 필드는 AI가 새로 더해도 자동으로 빠진다
 * (차단 목록이 아니라 허용 목록인 이유). 값 자체는 손대지 않고 {@code deepCopy}로 옮긴다.</p>
 */
final class ChatLogProjection {

    /** FE가 기록 복원·표시에 쓰는 필드. 새 필드가 필요하면 여기에 더한다. */
    static final List<String> ALLOWED_FIELDS = List.of(
            "id", "chatNo", "sessionId", "message", "reply", "engine", "modelName", "intent", "type",
            "tool", "toolData", "ui", "riskLevel", "recommendedAction", "reservationRequired", "createdAt");

    private ChatLogProjection() {
    }

    /** 기록 1건. 객체가 아니면 {@code null}(호출부가 오류로 처리). */
    static ObjectNode project(JsonNode log) {
        if (log == null || !log.isObject()) {
            return null;
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String field : ALLOWED_FIELDS) {
            JsonNode value = log.get(field);
            if (value != null) {
                out.set(field, value.deepCopy());
            }
        }
        return out;
    }

    /** 기록 목록. 배열이 아니거나 객체가 아닌 원소가 섞여 있으면 {@code null}. */
    static ArrayNode projectAll(JsonNode logs) {
        if (logs == null || !logs.isArray()) {
            return null;
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode log : logs) {
            ObjectNode projected = project(log);
            if (projected == null) {
                return null;
            }
            out.add(projected);
        }
        return out;
    }
}
