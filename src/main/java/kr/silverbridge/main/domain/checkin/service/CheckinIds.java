package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.repository.CheckinSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 말벗 식별자. 세션 ID는 {@code ck_} + 영숫자 24자 무작위 값이다 - AI 서버·로그로 나가는 값이라 사용자 식별자를 넣지 않는다
 * (카메라 세션 ID 2026-10-05 규칙과 같다).
 */
@Component
@RequiredArgsConstructor
public class CheckinIds {

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SESSION_ID = Pattern.compile("\\Ack_[A-Za-z0-9]{24}\\z");
    private static final Pattern USER_ID = Pattern.compile("\\A[A-Za-z0-9]{1,6}\\z");

    private final CheckinSessionRepository sessionRepository;

    public String newSessionId() {
        String id;
        do {
            id = "ck_" + random(24);
        } while (sessionRepository.existsBySessionId(id));
        return id;
    }

    /** 경로로 받은 세션 ID가 우리가 만든 형식인지. 아니면 DB를 보지 않고 404다. */
    public static boolean isSessionIdFormat(String sessionId) {
        return sessionId != null && SESSION_ID.matcher(sessionId).matches();
    }

    public static boolean isUserIdFormat(String userId) {
        return userId != null && USER_ID.matcher(userId).matches();
    }

    public static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
