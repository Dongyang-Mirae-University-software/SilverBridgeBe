package kr.silverbridge.main.global.util;

/**
 * 사용자가 보낸 식별자(경로 변수·쿼리·STOMP destination)를 로그에 남길 때 쓰는 정리 도구 (2026-11-10 전체 점검 M-1·L-1·L-2).
 *
 * <p>원문을 그대로 남기면 줄바꿈 삽입으로 로그 줄을 위조하거나 긴 값으로 로그를 불릴 수 있다. 영숫자와 {@code _ - . /}만
 * 두고 나머지는 {@code ?}로 바꾸며, 64자를 넘으면 자르고 원래 길이를 붙인다. 형식이 정해진 값(사용자 ID 등)은 이것 대신
 * 형식 검사 후 길이만 남기는 쪽을 먼저 쓴다.</p>
 */
public final class LogSafe {

    private static final int MAX_LENGTH = 64;

    private LogSafe() {
    }

    public static String id(String value) {
        if (value == null) {
            return "null";
        }
        int shown = Math.min(value.length(), MAX_LENGTH);
        StringBuilder out = new StringBuilder(shown + 16);
        for (int i = 0; i < shown; i++) {
            char c = value.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/';
            out.append(safe ? c : '?');
        }
        if (value.length() > MAX_LENGTH) {
            out.append("...(len=").append(value.length()).append(')');
        }
        return out.toString();
    }
}
