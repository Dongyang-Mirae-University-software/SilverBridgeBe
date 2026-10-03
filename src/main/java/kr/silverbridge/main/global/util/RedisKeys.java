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
    // 식별자: 가입 계정은 user.id(H-2), 미가입 이메일은 정규화 이메일의 SHA-256 hex(AUTH-G25 - 응답을 가입 이메일과 같게)
    public static final String LOGIN_FAIL = "login:fail:";
    public static final String LOGIN_LOCK = "login:lock:";
    // 비밀번호 변경·회원 탈퇴의 "현재 비밀번호" 확인 실패 카운터·잠금 (USER-G05, 키: userId).
    // 로그인 키와 섞지 않는다 - 탈취한 access token으로 이 경로를 두드려도 본인 로그인까지 잠기지 않게.
    public static final String USER_PW_FAIL = "user:pwfail:";
    public static final String USER_PW_LOCK = "user:pwlock:";

    // ── 로그인으로 밀려난 refresh token 구분 (AUTH-G03) ──
    // 값: 마지막 로그인에서 발급한 refresh token의 iat(epoch 초). 키: userId. TTL = 그 토큰의 수명.
    // 이 값보다 먼저 발급된 refresh가 DB에 없으면 "다른 기기 로그인으로 밀려난 토큰"이라 재사용 감지(전체 폐기) 없이 401만 준다.
    public static final String REFRESH_LOGIN_AT = "refresh:login-at:";

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

    // ── 같은 (보호자, 피보호자) 쌍의 연결 요청 반복 제한 (CONN-G04, 2026-10-02) ──
    // 키: guardianId + ":" + wardId. 값: 24시간 고정 윈도우 안에 실제로 만들어진 요청 수. 수락(ACTIVE) 시 삭제.
    public static final String CONNECTION_REQUEST_COUNT = "connection:request:count:";

    // ── 카메라 영상 스트림 티켓 (2026-10-03) ──────────────
    // 키: 티켓 문자열. 값: "{userId}:{sessionId}:{발급 epoch ms}". TTL 60초, 1회 소비(GETDEL).
    // <img>는 Authorization 헤더를 못 보내서 access token 대신 이 티켓을 영상 주소에 붙인다.
    public static final String CAMERA_STREAM_TICKET = "camera:stream:ticket:";

    // ws:connected:(WebSocket 접속 상태)는 2026-10-02 삭제 - 쓰기만 하고 읽는 곳이 없었고, CONNECTED 프레임에
    // 세션 속성이 없어 실제로는 기록도 되지 않았다(XCUT-G30). 접속 여부가 필요해지면 연결 수 카운터로 새로 설계할 것.
}
