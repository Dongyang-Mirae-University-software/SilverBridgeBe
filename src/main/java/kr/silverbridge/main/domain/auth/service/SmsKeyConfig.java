package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.util.RedisKeys;

/**
 * SMS 인증 흐름별 Redis 키 prefix 묶음
 * 회원가입과 비밀번호 재설정은 동일한 인증 플로우지만 키 네임스페이스가 분리되어 있음
 */
public record SmsKeyConfig(
        String verifyPrefix,
        String cooldownPrefix,
        String attemptPrefix
) {
    public String verifyKey(String phone) {
        return verifyPrefix + phone;
    }

    public String cooldownKey(String phone) {
        return cooldownPrefix + phone;
    }

    public String attemptKey(String phone) {
        return attemptPrefix + phone;
    }

    /** 회원가입 SMS 인증 */
    public static final SmsKeyConfig SIGNUP = new SmsKeyConfig(
            RedisKeys.SMS_VERIFY,
            RedisKeys.SMS_COOLDOWN,
            RedisKeys.SMS_ATTEMPT
    );

    /** 비밀번호 재설정 SMS 인증 */
    public static final SmsKeyConfig PASSWORD_RESET = new SmsKeyConfig(
            RedisKeys.PW_SMS_VERIFY,
            RedisKeys.PW_SMS_COOLDOWN,
            RedisKeys.PW_SMS_ATTEMPT
    );
}
