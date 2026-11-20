package kr.silverbridge.main.domain.medication.entity;

import kr.silverbridge.main.global.validation.TextSanitizer;

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
 *   <li>같은 약의 다른 용량이 함께 있어도 늘 짧은 이름이다(2026-11-19 사용자 결정 - 복용량·시각으로 구분).</li>
 *   <li>결과가 비면 원본을 돌려준다.</li>
 * </ul>
 */
public final class MedicationDisplayName {

    private static final String UNIT = "(?:밀리그램|밀리그람|마이크로그램|마이크로그람|그램|그람|밀리리터|리터|국제단위|단위|퍼센트"
            + "|mg|MG|㎎|mcg|μg|㎍|mL|ml|ML|㎖|IU|g|L|%)";
    private static final String NUMBER = "[0-9][0-9.,]*";

    /** 끝쪽 용량 덩어리: "500밀리그램", "500/5밀리그램", "125mg/5mL", "50밀리그램/밀리리터", 단위 없는 "500/20". */
    private static final Pattern TRAILING_DOSE = Pattern.compile(
            "^(.*?)(\\s*" + NUMBER + "\\s*" + UNIT + "?(?:\\s*/\\s*(?:" + NUMBER + ")?\\s*" + UNIT + "?)*)$");
    private static final Pattern HAS_UNIT = Pattern.compile("[0-9]\\s*" + UNIT + "|/\\s*" + UNIT);

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
