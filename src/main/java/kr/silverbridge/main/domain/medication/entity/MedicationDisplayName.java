package kr.silverbridge.main.domain.medication.entity;

import kr.silverbridge.main.global.validation.TextSanitizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 약 표시 이름 규칙(2026-11-19). 저장된 이름({@code medication.name})은 허가 품목명 원본이라
 * "다이아벡스정500밀리그램(메트포르민염산염)"처럼 길다. 화면에는 끝쪽 성분 괄호와 용량을 뗀 "다이아벡스정"만 보인다.
 *
 * <ul>
 *   <li>저장 값은 바꾸지 않는다 - 허가 품목 대조·약사 확인용 원본이다. 이 규칙은 응답에서만 쓴다.</li>
 *   <li>끝쪽 괄호({@code (…)}·{@code […]}, 여러 개 연속 포함)는 뗀 앞부분이 <b>용량이나 제형어(정·캡슐·시럽 등)로 끝날 때만</b> 뗀다.
 *       직접 입력한 "혈압약 (암로디핀 5mg)"·"아침약(혈압)"은 그대로 남는다(사용자가 일부러 적은 설명이다).</li>
 *   <li>끝쪽 용량은 단위(밀리그램·mg·%·IU·mL 등, {@code /5mL} 같은 농도 포함)가 있거나 앞이 제형어일 때만 뗀다.
 *       이름 가운데 숫자("타세놀8시간이알서방정")와 끝이 단위 없는 숫자인 이름("비타민D3")은 그대로다.</li>
 *   <li>같은 피보호자의 약 중 짧은 이름이 겹치면 그 약들만 "짧은 이름(용량)"이다 - {@link #ofAll}
 *       (2026-11-20 사용자 결정, 같은 날의 "겹쳐도 늘 짧은 이름"을 번복). 용량 단위는 약자로 통일한다(mg·mcg·g·mL·L·IU·%).</li>
 *   <li>결과가 비면 원본을 돌려준다.</li>
 * </ul>
 */
public final class MedicationDisplayName {

    /** 용량 단위 → 약자. 겹친 약의 "(용량)"에만 쓴다. "단위"는 원문 그대로 둔다. */
    private static final Map<String, String> UNIT_ABBREVIATION = Map.ofEntries(
            Map.entry("밀리그램", "mg"), Map.entry("밀리그람", "mg"), Map.entry("mg", "mg"), Map.entry("MG", "mg"), Map.entry("㎎", "mg"),
            Map.entry("마이크로그램", "mcg"), Map.entry("마이크로그람", "mcg"), Map.entry("mcg", "mcg"), Map.entry("μg", "mcg"), Map.entry("㎍", "mcg"),
            Map.entry("그램", "g"), Map.entry("그람", "g"), Map.entry("g", "g"),
            Map.entry("밀리리터", "mL"), Map.entry("mL", "mL"), Map.entry("ml", "mL"), Map.entry("ML", "mL"), Map.entry("㎖", "mL"),
            Map.entry("리터", "L"), Map.entry("L", "L"),
            Map.entry("국제단위", "IU"), Map.entry("IU", "IU"), Map.entry("단위", "단위"),
            Map.entry("퍼센트", "%"), Map.entry("%", "%"));

    private static final String UNIT = "(?:밀리그램|밀리그람|마이크로그램|마이크로그람|그램|그람|밀리리터|리터|국제단위|단위|퍼센트"
            + "|mg|MG|㎎|mcg|μg|㎍|mL|ml|ML|㎖|IU|g|L|%)";
    /** 소유 수량자(*+) - 숫자 덩어리를 되돌려 나눠 보지 않게 한다(점검 L-1: "가1,1,1,…x" 100자가 약 1ms 걸렸다). */
    private static final String NUMBER = "[0-9][0-9.,]*+";

    /** 끝쪽 용량 덩어리: "500밀리그램", "500/5밀리그램", "125mg/5mL", "50밀리그램/밀리리터", 단위 없는 "500/20". */
    private static final Pattern TRAILING_DOSE = Pattern.compile(
            "^(.*?)(\\s*" + NUMBER + "\\s*" + UNIT + "?(?:\\s*/\\s*(?:" + NUMBER + ")?\\s*" + UNIT + "?)*)$");
    private static final Pattern HAS_UNIT = Pattern.compile("[0-9]\\s*" + UNIT + "|/\\s*" + UNIT);
    private static final Pattern UNIT_TOKEN = Pattern.compile(UNIT);
    private static final Pattern BRACKET_CONTENT = Pattern.compile("[(\\[]([^()\\[\\]]*)[)\\]]");
    /** 괄호 안이 용량뿐인지("5mg", "500/5밀리그램") - "아모디핀정(5mg)"처럼 용량이 괄호 안에 있을 때. */
    private static final Pattern DOSE_ONLY = Pattern.compile(
            "\\s*" + NUMBER + "\\s*" + UNIT + "?(?:\\s*/\\s*(?:" + NUMBER + ")?\\s*" + UNIT + "?)*\\s*");

    /** 제형어. 이 말로 끝나는 앞부분이면 허가 품목명 꼴로 본다. "약"은 넣지 않는다(직접 입력 "혈압약 (…)" 보호). */
    private static final Pattern ENDS_WITH_FORM = Pattern.compile(
            "(?:정|정제|캡슐|캅셀|시럽|액|주|주사|산|과립|환|패취|패치|연고|크림|겔|젤|로션|좌제|좌약|스프레이|흡입제|분말|트로키|필름)$");

    private MedicationDisplayName() {
    }

    public static String of(String name) {
        if (name == null) {
            return null;
        }
        String original = name.strip();
        String result = stripTrailingDose(stripTrailingBrackets(original));
        return TextSanitizer.hasVisibleChar(result) ? result : original;
    }

    /**
     * 한 피보호자의 약 이름들에 대한 표시 이름(2026-11-20). 키는 앞뒤 공백을 뗀 원래 이름이다.
     *
     * <ol>
     *   <li>짧은 이름({@link #of})이 겹치지 않으면 짧은 이름 그대로.</li>
     *   <li>겹치는 약끼리는 "짧은 이름(용량 약자)" - 예: 다이아벡스정(500mg) / 다이아벡스정(850mg).</li>
     *   <li>떼어 낼 용량이 없거나 용량까지 같으면 끝쪽 괄호만 뗀 이름, 그것도 같으면 짧은 이름 그대로.</li>
     *   <li>원래 이름까지 같은 약(같은 약을 두 번 등록)은 한 이름으로 보고 겹침으로 세지 않는다.</li>
     * </ol>
     */
    public static Map<String, String> ofAll(Collection<String> names) {
        Map<String, List<String>> byShort = new LinkedHashMap<>();
        names.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .distinct()
                .forEach(original -> byShort.computeIfAbsent(of(original), key -> new ArrayList<>()).add(original));

        Map<String, String> result = new HashMap<>();
        byShort.forEach((shortName, originals) -> {
            if (originals.size() == 1) {
                result.put(originals.get(0), shortName);
                return;
            }
            Map<String, String> withDose = new HashMap<>();
            Map<String, String> bracketless = new HashMap<>();
            for (String original : originals) {
                String dose = doseOf(original);
                if (dose != null) {
                    withDose.put(original, shortName + "(" + dose + ")");
                }
                bracketless.put(original, stripTrailingBrackets(original));
            }
            for (String original : originals) {
                String candidate = withDose.get(original);
                if (candidate != null && occurrences(withDose, candidate) == 1) {
                    result.put(original, candidate);
                } else if (occurrences(bracketless, bracketless.get(original)) == 1
                        && TextSanitizer.hasVisibleChar(bracketless.get(original))) {
                    result.put(original, bracketless.get(original));
                } else {
                    result.put(original, shortName);
                }
            }
        });
        return result;
    }

    private static long occurrences(Map<String, String> values, String value) {
        return values.values().stream().filter(value::equals).count();
    }

    /** 원래 이름에서 뗀 용량(약자로 통일), 없으면 null. 끝쪽 용량이 없으면 뗀 괄호 중 용량뿐인 것을 쓴다. */
    static String doseOf(String original) {
        String base = stripTrailingBrackets(original);
        String stripped = stripTrailingDose(base);
        if (!stripped.equals(base)) {
            return abbreviate(base.substring(stripped.length()));
        }
        if (base.length() < original.length()) {
            Matcher bracket = BRACKET_CONTENT.matcher(original.substring(base.length()));
            while (bracket.find()) {
                String inner = bracket.group(1);
                if (DOSE_ONLY.matcher(inner).matches() && HAS_UNIT.matcher(inner).find()) {
                    return abbreviate(inner);
                }
            }
        }
        return null;
    }

    private static String abbreviate(String dose) {
        String compact = dose.replaceAll("\\s+", "");
        return UNIT_TOKEN.matcher(compact).replaceAll(m -> Matcher.quoteReplacement(
                UNIT_ABBREVIATION.getOrDefault(m.group(), m.group())));
    }

    /** 끝쪽 괄호 묶음을 모두 떼어 본 뒤, 남은 앞부분이 용량·제형어로 끝날 때만 뗀 결과를 쓴다. */
    private static String stripTrailingBrackets(String name) {
        String base = name;
        while (!base.isEmpty()) {
            int open = matchingOpenBracket(base);
            if (open < 0) {
                break;
            }
            base = base.substring(0, open).stripTrailing();
        }
        if (base.equals(name) || base.isEmpty()) {
            return name;
        }
        return ENDS_WITH_FORM.matcher(base).find() || isDoseTail(base) ? base : name;
    }

    /** 끝 글자가 닫는 괄호면 짝이 되는 여는 괄호 위치(중첩 허용), 아니면 -1. 짝이 안 맞으면 -1. */
    private static int matchingOpenBracket(String s) {
        char last = s.charAt(s.length() - 1);
        if (last != ')' && last != ']') {
            return -1;
        }
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isDoseTail(String name) {
        return !stripTrailingDose(name).equals(name);
    }

    private static String stripTrailingDose(String name) {
        Matcher m = TRAILING_DOSE.matcher(name);
        if (!m.matches()) {
            return name;
        }
        String prefix = m.group(1).stripTrailing();
        String dose = m.group(2);
        if (prefix.isEmpty() || Character.isDigit(prefix.charAt(prefix.length() - 1))) {
            return name;
        }
        boolean strip = HAS_UNIT.matcher(dose).find() || ENDS_WITH_FORM.matcher(prefix).find();
        return strip ? prefix : name;
    }
}
