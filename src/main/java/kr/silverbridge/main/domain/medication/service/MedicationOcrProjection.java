package kr.silverbridge.main.domain.medication.service;

import com.fasterxml.jackson.databind.JsonNode;
import kr.silverbridge.main.domain.medication.dto.MedicationOcrResponse;
import kr.silverbridge.main.domain.medication.entity.MedicationTimeSlot;
import kr.silverbridge.main.global.validation.TextSanitizer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI OCR 응답을 <b>허용 목록</b>으로 걸러 {@link MedicationOcrResponse}로 옮긴다(2026-10-22, 챗 기록 {@code ChatLogProjection}과
 * 같은 판단). 읽는 필드는 아래 코드에 적힌 것뿐이라 AI가 성분·{@code reason}·새 필드를 보내도 응답에 실리지 않는다.
 *
 * <p>값이 범위 밖이거나 형식이 다르면 그 칸만 {@code null}로 비운다(약 전체를 버리지 않는다 - 이름은 맞을 수 있다).
 * 이름이 보이는 글자로 남지 않는 약은 초안이 될 수 없어 뺀다.</p>
 *
 * <p>약 이름 대조 칸({@code inList}·{@code suggestions}, 2026-10-23)은 AI가 보내지 않으면 {@code null}·빈 배열이다
 * - 옛 AI 응답도 그대로 받는다.</p>
 */
final class MedicationOcrProjection {

    static final int MAX_DRUGS = 20;
    static final int MAX_NAME_CHARS = 100;
    static final int MAX_TIMING_CHARS = 50;
    static final int MAX_SUGGESTIONS = 3;
    private static final double MAX_PER_DOSE = 99;
    private static final int MAX_TIMES_PER_DAY = 10;
    private static final int MAX_DAYS = 365;
    private static final Set<String> CONFIDENCE_VALUES = Set.of("high", "low");

    private MedicationOcrProjection() {
    }

    /** 형식이 계약과 다르면(readable 없음·drugs가 배열 아님) {@code null} - 호출부가 503으로 처리한다. */
    static MedicationOcrResponse project(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode readable = root.get("readable");
        if (readable == null || !readable.isBoolean()) {
            return null;
        }
        JsonNode drugs = root.get("drugs");
        if (drugs != null && !drugs.isNull() && !drugs.isArray()) {
            return null;
        }
        if (!readable.booleanValue() || drugs == null || drugs.isNull()) {
            return new MedicationOcrResponse(readable.booleanValue(), List.of());
        }
        List<MedicationOcrResponse.Drug> out = new ArrayList<>();
        for (JsonNode drug : drugs) {
            if (out.size() >= MAX_DRUGS) {
                break;
            }
            MedicationOcrResponse.Drug projected = projectDrug(drug);
            if (projected != null) {
                out.add(projected);
            }
        }
        return new MedicationOcrResponse(true, List.copyOf(out));
    }

    private static MedicationOcrResponse.Drug projectDrug(JsonNode drug) {
        if (drug == null || !drug.isObject()) {
            return null;
        }
        String name = text(drug.get("name"), MAX_NAME_CHARS);
        if (name == null) {
            return null;
        }
        return new MedicationOcrResponse.Drug(
                name,
                perDose(drug.get("perDose")),
                integer(drug.get("timesPerDay"), MAX_TIMES_PER_DAY),
                integer(drug.get("days"), MAX_DAYS),
                slots(drug.get("slots")),
                text(drug.get("timing"), MAX_TIMING_CHARS),
                confidence(drug.get("confidence")),
                inList(drug.get("inList")),
                suggestions(drug.get("suggestions")));
    }

    /** 한 줄로 정리하고 보이는 글자가 없으면 {@code null}, 길면 코드포인트 기준으로 자른다. */
    private static String text(JsonNode node, int maxChars) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String sanitized = TextSanitizer.sanitize(node.textValue());
        if (!TextSanitizer.hasVisibleChar(sanitized)) {
            return null;
        }
        if (sanitized.codePointCount(0, sanitized.length()) <= maxChars) {
            return sanitized;
        }
        return sanitized.substring(0, sanitized.offsetByCodePoints(0, maxChars)).strip();
    }

    private static Double perDose(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.doubleValue();
        return value > 0 && value <= MAX_PER_DOSE ? value : null;
    }

    private static Integer integer(JsonNode node, int max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        int value = node.intValue();
        return value >= 1 && value <= max ? value : null;
    }

    /** 모르는 값은 버리고, 중복은 한 번만, 순서는 하루 흐름(아침→취침 전)이다. */
    private static List<MedicationTimeSlot> slots(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        EnumSet<MedicationTimeSlot> found = EnumSet.noneOf(MedicationTimeSlot.class);
        for (JsonNode slot : node) {
            if (!slot.isTextual()) {
                continue;
            }
            for (MedicationTimeSlot candidate : MedicationTimeSlot.values()) {
                if (candidate.name().equals(slot.textValue())) {
                    found.add(candidate);
                }
            }
        }
        return List.copyOf(found);
    }

    /** JSON boolean일 때만 값이 있고, 없거나 형식이 다르면 {@code null}(모름). */
    private static Boolean inList(JsonNode node) {
        return node != null && node.isBoolean() ? node.booleanValue() : null;
    }

    /** 이름과 같은 규칙(정리·빈 값 제외·100자)으로 거르고, 중복은 한 번만, 앞에서부터 최대 3개다. */
    private static List<String> suggestions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (JsonNode suggestion : node) {
            if (found.size() >= MAX_SUGGESTIONS) {
                break;
            }
            String name = text(suggestion, MAX_NAME_CHARS);
            if (name != null) {
                found.add(name);
            }
        }
        return List.copyOf(found);
    }

    private static String confidence(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        return CONFIDENCE_VALUES.contains(node.textValue()) ? node.textValue() : null;
    }
}
