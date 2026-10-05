package kr.silverbridge.main.global.jwt;

/**
 * 토큰 무효화 기준 시각(Redis {@code password:invalidate:{userId}})의 저장·비교 규칙.
 *
 * <p>JWT iat는 초 단위(NumericDate)라, 무효화 시각을 ms로 두고 {@code iat <= 무효화}로 비교하면
 * 무효화와 같은 초에 새로 발급한 토큰까지 거부된다(AUTH-G06·ADMIN-G03·USER-G13·XCUT-G10).
 * 그래서 무효화 시각을 <b>초 단위(내림)</b>로 저장하고 {@code iat(초) < 무효화(초)}일 때만 거부한다.</p>
 *
 * <p>수용한 대가: 무효화와 같은 초 안에, 무효화 직전에 발급된 옛 토큰은 살아남는다(최대 1초 창).
 * 2026-10-02 결정(D2) - 방금 받은 새 토큰이 거부되는 쪽이 더 나쁘다고 판단했다.
 * HTTP 필터와 WS 핸드셰이크가 반드시 이 클래스로 같은 비교를 해야 한다.</p>
 */
public final class TokenInvalidation {

    // 배포 전 ms로 저장된 값과 구분하는 경계. 1e11초는 서기 5138년, 1e11ms는 1973년이라 두 해석이 겹치지 않는다.
    // 옛 ms 값은 TTL(access 만료 30분)이 지나면 사라지지만, 그 사이에도 초로 환산해 같은 규칙으로 비교한다.
    private static final long LEGACY_MILLIS_THRESHOLD = 100_000_000_000L;

    private TokenInvalidation() {}

    // 저장할 값 - 무효화 시각(epoch ms)을 초로 내림한 문자열
    public static String encode(long epochMillis) {
        return String.valueOf(Math.floorDiv(epochMillis, 1000L));
    }

    // 무효화 시각은 언제나 "기록한 순간"이라 지금보다 미래일 수 없다. 서버 간 시계 차이만 허용하는 여유(초).
    // 이보다 먼 미래 값은 손상으로 본다 - 그대로 비교하면 재로그인한 새 토큰까지 TTL 동안 계속 401이 되어
    // "로그인해도 바로 로그아웃"이 반복된다(XCUT-G03).
    static final long FUTURE_SKEW_SECONDS = 300L;

    // 저장된 값을 epoch 초로 해석한다(시각 검사 없음). 숫자가 아니면 CorruptValueException.
    public static long parseEpochSecond(String stored) {
        long value;
        try {
            value = Long.parseLong(stored.trim());
        } catch (NumberFormatException e) {
            throw new CorruptValueException("non-numeric");
        }
        return value >= LEGACY_MILLIS_THRESHOLD ? Math.floorDiv(value, 1000L) : value;
    }

    /**
     * 저장된 값을 epoch 초로 해석하고, 있을 수 없는 값(숫자 아님·음수·지금보다 먼 미래)이면 {@link CorruptValueException}.
     * HTTP 필터·WS 핸드셰이크는 이 메서드를 쓴다 - 손상 값도 조회 오류처럼 <b>통과시키지 않는다</b>(일반 경로 503,
     * SOS만 통과). 정상 값의 비교 규칙({@link #isRevoked})은 그대로다.
     */
    public static long parseEpochSecond(String stored, long nowMillis) {
        long sec = parseEpochSecond(stored);
        if (sec < 0) {
            throw new CorruptValueException("negative");
        }
        if (sec > Math.floorDiv(nowMillis, 1000L) + FUTURE_SKEW_SECONDS) {
            throw new CorruptValueException("future");
        }
        return sec;
    }

    /**
     * 무효화 저장값이 손상됨(조회는 성공했으나 값이 규칙에 맞지 않음). 조회 오류(연결 장애)와 로그 태그를 나눠
     * 운영자가 "Redis가 죽었다"와 "키 하나가 깨졌다"를 구분하게 한다. 메시지는 고정 사유 코드뿐이다(저장값 원문 금지).
     */
    public static final class CorruptValueException extends RuntimeException {
        public CorruptValueException(String reason) {
            super(reason, null, false, false);
        }
    }

    // iat(epoch ms, 초 단위로 절삭된 값)가 무효화 초보다 앞선 초면 무효. 같은 초는 허용한다.
    public static boolean isRevoked(long issuedAtMillis, long invalidatedEpochSecond) {
        return Math.floorDiv(issuedAtMillis, 1000L) < invalidatedEpochSecond;
    }
}
