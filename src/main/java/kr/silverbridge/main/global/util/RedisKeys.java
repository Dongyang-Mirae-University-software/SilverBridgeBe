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
    // per-phone 발송 건수 카운터 (A-M3) — IP 우회 SMS 폭탄/비용 남용 방어용 시간당 상한
    public static final String SMS_SEND_COUNT = "sms:sendcount:";

    // ── 비밀번호 재설정 ────────────────────────────────
    // UUID 토큰 폐지(2026-05): 6자리 코드로 통일. PW_RESET(토큰→userId) 키 제거됨.
    public static final String PW_SMS_VERIFY     = "password:sms:verify:";
    public static final String PW_SMS_ATTEMPT    = "password:sms:attempt:";
    public static final String PW_EMAIL_VERIFY   = "password:email:verify:";
    public static final String PW_EMAIL_ATTEMPT  = "password:email:attempt:";
    // per-email 발송 건수 카운터 (2026-05-23) — IP 회전으로 IP RateLimit을 우회한 특정 이메일
    // enumeration·메일 폭탄·비용 남용 차단용 시간당 상한. SMS의 sms:sendcount(A-M3)와 대칭.
    public static final String PW_EMAIL_SEND_COUNT = "password:email:sendcount:";

    // ── 카카오 OAuth ───────────────────────────────────
    public static final String KAKAO_PENDING = "kakao:pending:";

    // ── 로그인 보안 ────────────────────────────────────
    public static final String LOGIN_FAIL = "login:fail:";
    public static final String LOGIN_LOCK = "login:lock:";

    // ── 로그아웃 토큰 블랙리스트 ────────────────────────
    public static final String LOGOUT_TOKEN = "logout:";

    // ── 비밀번호 변경 후 토큰 무효화 ────────────────────
    // 값: 무효화 시각(epoch 초, 내림). 토큰 iat(초)가 이 값보다 작으면 401 처리 - 같은 초 발급은 허용(D2, 2026-10-02).
    // 비교·해석 규칙은 TokenInvalidation 한 곳에 둔다(배포 전 ms 값도 초로 환산해 읽는다).
    // 비밀번호 변경·재설정·탈퇴·정지·역할 변경이 공통으로 쓴다.
    // TTL은 access token 만료시간과 동일하게 두어 자연 만료 시 자동 정리.
    public static final String PASSWORD_INVALIDATE = "password:invalidate:";

    // ── API 요청 속도 제한 ─────────────────────────────
    public static final String RATE_LIMIT = "rate:";

    // ws:connected:(WebSocket 접속 상태)는 2026-10-02 삭제 - 쓰기만 하고 읽는 곳이 없었고, CONNECTED 프레임에
    // 세션 속성이 없어 실제로는 기록도 되지 않았다(XCUT-G30). 접속 여부가 필요해지면 연결 수 카운터로 새로 설계할 것.
}
