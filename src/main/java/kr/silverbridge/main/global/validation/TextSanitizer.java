package kr.silverbridge.main.global.validation;

import java.text.Normalizer;

/**
 * 사용자 입력 문자열 정리·검사 유틸 (DTO 검증·서비스 정규화에서 공용).
 * <p>
 * 다루는 문제: NUL(\u0000)처럼 DB가 거절하는 문자(500 방지), 제로폭 문자·NBSP·전각공백만으로 이루어진
 * "겉보기엔 비어 있지 않은" 입력, 합성/분해 형태가 달라 같은 글자가 다르게 저장되는 유니코드 정규화 차이,
 * 이모지(서로게이트 쌍)가 {@code String.length()}로 2글자로 세어지는 문제.
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /** 한 줄 입력 정리: NFC → 제어문자·서식문자 제거 → 공백류를 ' '로 통일·연속 공백 1개로 → 앞뒤 trim. null은 null. */
    public static String sanitize(String value) {
        return sanitize(value, false);
    }

    /** 여러 줄 입력 정리: {@link #sanitize}와 같되 줄바꿈(\n, \r\n→\n)은 보존하고 줄 단위로 공백을 정리한다. null은 null. */
    public static String sanitizeMultiline(String value) {
        return sanitize(value, true);
    }

    private static String sanitize(String value, boolean keepLineBreaks) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        int[] cps = normalized.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (keepLineBreaks && (cp == '\n' || cp == '\r')) {
                if (cp == '\r' && i + 1 < cps.length && cps[i + 1] == '\n') {
                    continue; // \r\n → \n (\n 쪽에서 처리)
                }
                trimTrailingSpace(sb);
                sb.append('\n');
                pendingSpace = false;
                continue;
            }
            if (isControl(cp) || isFormat(cp)) {
                continue;
            }
            if (isSpaceLike(cp)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append(' ');
            }
            pendingSpace = false;
            sb.appendCodePoint(cp);
        }
        String result = sb.toString();
        return keepLineBreaks ? stripLineBreaksAtEdges(result) : result;
    }

    private static void trimTrailingSpace(StringBuilder sb) {
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == ' ') {
            len--;
        }
        sb.setLength(len);
    }

    private static String stripLineBreaksAtEdges(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '\n') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(start, end);
    }

    /** NUL 포함 제어문자(Cc)를 모두 제거한다(탭·줄바꿈 포함). null은 null. */
    public static String removeControlChars(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().filter(cp -> !isControl(cp)).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** 제어문자(Cc)가 하나라도 있는지. {@code allowLineBreaks}면 \t \n \r은 허용한다. null은 false. */
    public static boolean containsControlChars(String value, boolean allowLineBreaks) {
        if (value == null) {
            return false;
        }
        return value.codePoints().anyMatch(cp -> isControl(cp)
                && !(allowLineBreaks && (cp == '\t' || cp == '\n' || cp == '\r')));
    }

    /** 제로폭(U+200B~200D, U+FEFF, U+2060 등) 서식문자(Cf)를 제거한다. null은 null. */
    public static String removeFormatChars(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().filter(cp -> !isFormat(cp)).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** 코드포인트(이모지 1개 = 1) 기준 길이. null은 0. 정리하지 않은 원문 그대로 센다. */
    public static int codePointLength(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    /** {@link #sanitize} 한 결과의 코드포인트 길이 — 사용자가 보는 글자 수에 가깝다. null은 0. */
    public static int visibleLength(String value) {
        return codePointLength(sanitize(value));
    }

    /** 눈에 보이는 글자가 1자 이상 있는지(공백·제어·서식·결합문자 단독·한글 채움문자 등만이면 false). null은 false. */
    public static boolean hasVisibleChar(String value) {
        return value != null && value.codePoints().anyMatch(TextSanitizer::isVisible);
    }

    private static boolean isControl(int cp) {
        return Character.getType(cp) == Character.CONTROL;
    }

    private static boolean isFormat(int cp) {
        return Character.getType(cp) == Character.FORMAT;
    }

    private static boolean isSpaceLike(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean isVisible(int cp) {
        if (isSpaceLike(cp) || isInvisibleFiller(cp)) {
            return false;
        }
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.SURROGATE,
                 Character.PRIVATE_USE,
                 Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> false;
            default -> true;
        };
    }

    // 범주는 문자(Lo 등)지만 화면에 아무것도 그리지 않는 채움 문자
    private static boolean isInvisibleFiller(int cp) {
        return cp == 0x3164 || cp == 0x115F || cp == 0x1160 || cp == 0xFFA0 // 한글 채움
                || cp == 0x2800                                              // 점자 빈 칸
                || cp == 0x17B4 || cp == 0x17B5;                             // 크메르 고유 모음(보이지 않음)
    }
}
