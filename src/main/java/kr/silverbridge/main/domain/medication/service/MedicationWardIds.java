package kr.silverbridge.main.domain.medication.service;

import java.util.regex.Pattern;

/**
 * 경로로 받은 피보호자 ID의 형식 확인(2026-10-22 점검 L-6). 사용자 ID는 영숫자 1~6자다.
 *
 * <p>형식이 틀린 값은 DB 조회 없이 거절하고, {@code [IDOR-ATTEMPT]} 로그에는 원문 대신 길이만 남긴다 - 경로 변수 원문을
 * 로그에 남기면 줄바꿈(%0a) 삽입으로 로그 줄을 위조할 수 있다(챗 대리 상담 wardId 검증과 같은 방식).</p>
 */
final class MedicationWardIds {

    private static final Pattern USER_ID_FORMAT = Pattern.compile("\\A[A-Za-z0-9]{1,6}\\z");

    private MedicationWardIds() {
    }

    static boolean isWellFormed(String wardId) {
        return wardId != null && USER_ID_FORMAT.matcher(wardId).matches();
    }

    static int lengthOf(String wardId) {
        return wardId == null ? 0 : wardId.length();
    }
}
