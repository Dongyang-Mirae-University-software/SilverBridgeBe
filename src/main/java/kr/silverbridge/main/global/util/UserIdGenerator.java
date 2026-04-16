package kr.silverbridge.main.global.util;

import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 사용자 ID 생성기
 * 영숫자(a-z, A-Z, 0-9) 6자리 랜덤 생성, 중복 시 재시도
 */
@Component
@RequiredArgsConstructor
public class UserIdGenerator {

    private final UserRepository userRepository;

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ID_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        String id;
        do {
            id = generateRandom();
        } while (userRepository.existsById(id));
        return id;
    }

    private String generateRandom() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
