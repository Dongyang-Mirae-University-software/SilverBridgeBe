package kr.silverbridge.main.global.util;

/**
 * Redis 키 prefix 상수 모음
 * 실제 키 = prefix + 식별자 (예: RedisKeys.SMS_VERIFIED + phone)
 */
public final class RedisKeys {

    private RedisKeys() {}

    // ── 회원가입 SMS 인증 ──────────────────────────────
    public static final String SMS_VERIFY    = "sms:verify:";
    public static final String SMS_VERIFIED  = "sms:verified:";
    public static final String SMS_COOLDOWN  = "sms:cooldown:";
    public static final String SMS_ATTEMPT   = "sms:attempt:";

    // ── 비밀번호 재설정 ────────────────────────────────
    public static final String PW_RESET        = "password:reset:";
    public static final String PW_SMS_VERIFY   = "password:sms:verify:";
    public static final String PW_SMS_COOLDOWN = "password:sms:cooldown:";
    public static final String PW_SMS_ATTEMPT  = "password:sms:attempt:";

    // ── 카카오 OAuth ───────────────────────────────────
    public static final String KAKAO_PENDING = "kakao:pending:";

    // ── 로그인 보안 ────────────────────────────────────
    public static final String LOGIN_FAIL = "login:fail:";
    public static final String LOGIN_LOCK = "login:lock:";

    // ── 로그아웃 토큰 블랙리스트 ────────────────────────
    public static final String LOGOUT_TOKEN = "logout:";

    // ── API 요청 속도 제한 ─────────────────────────────
    public static final String RATE_LIMIT = "rate:";

    // ── 캐릭터 표정 (AI 서버 전달, 현재 상태 유지) ──────
    public static final String CHARACTER_EXPRESSION = "character:expression:";

    // ── WebSocket 접속 상태 ───────────────────────────
    public static final String WS_CONNECTED = "ws:connected:";
}
