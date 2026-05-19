package kr.silverbridge.main.global.util;

/**
 * Redis 키 prefix 상수 모음
 * 실제 키 = prefix + 식별자 (예: RedisKeys.SMS_VERIFIED + phone)
 */
public final class RedisKeys {

    private RedisKeys() {}

    // ── 회원가입 SMS 인증 ──────────────────────────────
    // 재발송 쿨다운 폐지(2026-05): cooldown 키 없음. 빈도 방어는 IP RateLimit에 의존.
    public static final String SMS_VERIFY    = "sms:verify:";
    public static final String SMS_VERIFIED  = "sms:verified:";
    public static final String SMS_ATTEMPT   = "sms:attempt:";

    // ── 비밀번호 재설정 ────────────────────────────────
    // UUID 토큰 폐지(2026-05): 6자리 코드로 통일. PW_RESET(토큰→userId) 키 제거됨.
    public static final String PW_SMS_VERIFY     = "password:sms:verify:";
    public static final String PW_SMS_ATTEMPT    = "password:sms:attempt:";
    public static final String PW_EMAIL_VERIFY   = "password:email:verify:";
    public static final String PW_EMAIL_ATTEMPT  = "password:email:attempt:";

    // ── 카카오 OAuth ───────────────────────────────────
    public static final String KAKAO_PENDING = "kakao:pending:";

    // ── 로그인 보안 ────────────────────────────────────
    public static final String LOGIN_FAIL = "login:fail:";
    public static final String LOGIN_LOCK = "login:lock:";

    // ── 로그아웃 토큰 블랙리스트 ────────────────────────
    public static final String LOGOUT_TOKEN = "logout:";

    // ── 비밀번호 변경 후 토큰 무효화 ────────────────────
    // 값: 비밀번호 변경 시각(epoch ms). 토큰 iat가 이 값 이하이면 401 처리.
    // TTL은 access token 만료시간과 동일하게 두어 자연 만료 시 자동 정리.
    public static final String PASSWORD_INVALIDATE = "password:invalidate:";

    // ── API 요청 속도 제한 ─────────────────────────────
    public static final String RATE_LIMIT = "rate:";

    // ── 캐릭터 표정 (AI 서버 전달, 현재 상태 유지) ──────
    public static final String CHARACTER_EXPRESSION = "character:expression:";

    // ── WebSocket 접속 상태 ───────────────────────────
    public static final String WS_CONNECTED = "ws:connected:";

    // ── 관리자 대시보드 캐시 ───────────────────────────
    public static final String ADMIN_DASHBOARD_SUMMARY = "admin:dashboard:summary";
}
