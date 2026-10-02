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

    // 저장된 값을 epoch 초로 해석한다. 숫자가 아니면 NumberFormatException(호출자가 저장소 오류로 다룬다).
    public static long parseEpochSecond(String stored) {
        long value = Long.parseLong(stored);
        return value >= LEGACY_MILLIS_THRESHOLD ? Math.floorDiv(value, 1000L) : value;
    }

    // iat(epoch ms, 초 단위로 절삭된 값)가 무효화 초보다 앞선 초면 무효. 같은 초는 허용한다.
    public static boolean isRevoked(long issuedAtMillis, long invalidatedEpochSecond) {
        return Math.floorDiv(issuedAtMillis, 1000L) < invalidatedEpochSecond;
    }
}
