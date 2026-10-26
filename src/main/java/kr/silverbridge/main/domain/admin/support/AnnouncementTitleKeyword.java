package kr.silverbridge.main.domain.admin.support;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.validation.TextSanitizer;

/**
 * 관리자 공지·임시저장 목록의 제목 검색어 정규화(2026-10-26). 두 목록이 같은 규칙을 쓰도록 한 곳에 둔다.
 * <ul>
 *   <li>제목 저장과 같은 {@link TextSanitizer#sanitize} 정리(NFC·보이지 않는 문자 제거·공백 정리) - 분해형 한글 입력도 저장값과 맞는다.</li>
 *   <li>정리 후 비면 null(검색 조건 없음).</li>
 *   <li>{@value #MAX_LENGTH}자(코드포인트) 초과는 400 {@code INVALID_INPUT} - 잘라서 검색하면 관리자가 모르는 조건으로 결과가 나온다.</li>
 *   <li>LIKE 메타문자 이스케이프. JPQL의 {@code escape '\'} 절과 짝을 이룬다(회원·문의·이상감지 관리자 검색과 같은 규칙).</li>
 *   <li>소문자화는 하지 않는다 - JPQL이 제목과 검색어 양쪽에 DB {@code lower()}를 건다. Java {@code toLowerCase()}와 DB {@code lower()}는
 *       일부 글자(터키어 İ 등)에서 결과가 달라 한쪽만 Java로 바꾸면 그 글자가 든 검색어가 맞지 않았다(2026-10-26 점검 L-2).</li>
 * </ul>
 */
public final class AnnouncementTitleKeyword {

    public static final int MAX_LENGTH = 50;

    private AnnouncementTitleKeyword() {
    }

    /** @return JPQL에 넘길 이스케이프된 검색어(대소문자는 원문 그대로), 검색 조건이 없으면 null */
    public static String normalize(String keyword) {
        String cleaned = TextSanitizer.sanitize(keyword);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return cleaned
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
