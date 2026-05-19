package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.util.RedisKeys;

/**
 * 인증 흐름별 Redis 키 prefix 묶음
 * 회원가입 SMS / 비밀번호 재설정 SMS / 비밀번호 재설정 이메일 등 동일 인증 플로우의 키 네임스페이스를 분리한다.
 * 식별자(phone 또는 email) 는 호출 시 전달.
 */
public record VerificationKeyConfig(
        String verifyPrefix,
        String attemptPrefix
) {
    public String verifyKey(String identifier) {
        return verifyPrefix + identifier;
    }

    public String attemptKey(String identifier) {
        return attemptPrefix + identifier;
    }

    /** 회원가입 SMS 인증 */
    public static final VerificationKeyConfig SIGNUP = new VerificationKeyConfig(
            RedisKeys.SMS_VERIFY,
            RedisKeys.SMS_ATTEMPT
    );

    /** 비밀번호 재설정 SMS 인증 */
    public static final VerificationKeyConfig PASSWORD_RESET = new VerificationKeyConfig(
            RedisKeys.PW_SMS_VERIFY,
            RedisKeys.PW_SMS_ATTEMPT
    );

    /** 비밀번호 재설정 이메일 인증 */
    public static final VerificationKeyConfig PASSWORD_RESET_EMAIL = new VerificationKeyConfig(
            RedisKeys.PW_EMAIL_VERIFY,
            RedisKeys.PW_EMAIL_ATTEMPT
    );
}
