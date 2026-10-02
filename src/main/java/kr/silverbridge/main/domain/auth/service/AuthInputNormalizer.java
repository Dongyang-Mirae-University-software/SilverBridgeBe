package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.validation.TextSanitizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 인증 흐름(가입·로그인·아이디/비밀번호 찾기·카카오) 입구의 입력 정규화를 한 곳에 둔다.
 * <p>
 * <b>이메일</b> - 앞뒤 공백 제거 + 소문자(Locale.ROOT). 저장·조회·Redis 키가 모두 이 값 기준이다(AUTH-G09·XCUT-G16·FEUX-G12).
 * 대소문자만 다른 두 계정이 생기거나, 가입 때와 다른 대소문자로 로그인·복구가 실패하던 문제를 막는다.
 * ⚠️ 이 정규화는 DB의 기존 이메일이 소문자로 맞춰진 뒤(V55)에만 정합하다 - V55 이전 데이터에 대문자가 남아 있으면
 * 소문자 조회로 그 계정을 찾지 못한다. V55는 대소문자만 다른 중복이 있으면 데이터를 바꾸기 전에 중단한다.
 * <p>
 * <b>이름</b> - {@link TextSanitizer#sanitize}(NFC, 제로폭·제어문자 제거, 공백류 통일, 앞뒤 trim). 가입과 찾기 조회가
 * 같은 규칙을 써야 "홍길동 "으로 가입한 사람이 "홍길동"으로 아이디를 찾는다(AUTH-G13·FEUX-G11). 실제 글자가 없으면 400.
 */
final class AuthInputNormalizer {

    // 카카오가 이메일을 주지 않을 때 쓰는 대체 이메일 형식(kakao_{숫자}@kakao.com). 일반 가입으로 선점하지 못하게 예약한다(AUTH-G08).
    private static final Pattern RESERVED_KAKAO_EMAIL = Pattern.compile("^kakao_\\d+@kakao\\.com$");

    private AuthInputNormalizer() {
    }

    /** 이메일 정규화 - trim + 소문자(Locale.ROOT). null은 null. */
    static String email(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 이름 정규화 - TextSanitizer.sanitize. 눈에 보이는 글자가 없으면 400(INVALID_INPUT). */
    static String name(String raw) {
        String sanitized = TextSanitizer.sanitize(raw);
        if (sanitized == null || !TextSanitizer.hasVisibleChar(sanitized)) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return sanitized;
    }

    /** 카카오 대체 이메일 형식인지(대소문자 무시). 정규화한 이메일을 넘긴다. */
    static boolean isReservedKakaoEmail(String normalizedEmail) {
        return normalizedEmail != null && RESERVED_KAKAO_EMAIL.matcher(normalizedEmail.toLowerCase(Locale.ROOT)).matches();
    }

    /** SHA-256 hex - 미가입 이메일의 로그인 시도 카운터 키·카카오 pendingToken 저장값에 원문 대신 쓴다. */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
