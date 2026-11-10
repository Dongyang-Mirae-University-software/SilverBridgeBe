package kr.silverbridge.main.global.util;

import java.util.regex.Pattern;

/**
 * 사용자 ID 형식 확인 - 영숫자 1~6자 (2026-11-10 2차 점검 L-12, 서비스 여러 곳에 복제돼 있던 정규식을 한 곳으로).
 *
 * <p>경로·쿼리로 받은 피보호자 ID가 이 형식이 아니면 DB 조회 없이 거절하고, 로그에는 원문 대신 길이만 남긴다
 * (줄바꿈 삽입으로 로그 줄을 위조하지 못하게). {@code \A...\z}라 끝 줄바꿈도 허용하지 않는다.</p>
 */
public final class UserIdFormat {

    private static final Pattern USER_ID_FORMAT = Pattern.compile("\\A[A-Za-z0-9]{1,6}\\z");

    private UserIdFormat() {
    }

    public static boolean isWellFormed(String id) {
        return id != null && USER_ID_FORMAT.matcher(id).matches();
    }
}
