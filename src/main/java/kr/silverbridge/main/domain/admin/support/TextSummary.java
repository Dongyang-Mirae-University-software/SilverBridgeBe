package kr.silverbridge.main.domain.admin.support;

/**
 * 목록 응답용 본문 축약(ADMIN-G24). 목록은 제목·상태 위주라 본문 전체를 실어 보낼 필요가 없고,
 * 5000자 본문이 페이지 크기만큼 쌓이면 응답이 커진다. 코드포인트 기준이라 이모지가 중간에서 잘리지 않는다.
 */
public final class TextSummary {

    /** 목록 항목 본문 축약 길이(코드포인트) */
    public static final int LIST_CONTENT_MAX = 100;

    private TextSummary() {
    }

    /** 앞 {@link #LIST_CONTENT_MAX}자만 남긴다. 이하이면 그대로, null은 null. 말줄임표는 붙이지 않는다(표시는 FE 몫). */
    public static String forList(String content) {
        return truncate(content, LIST_CONTENT_MAX);
    }

    public static String truncate(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
