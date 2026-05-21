package kr.silverbridge.main.global.jwt;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties properties;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret("test-secret-key-at-least-256-bits-long-for-hmac-sha256-algorithm");
        properties.setAccessTokenExpiration(30 * 60 * 1000L);      // 30분
        properties.setRefreshTokenExpiration(7 * 24 * 60 * 60 * 1000L); // 7일
        jwtTokenProvider = new JwtTokenProvider(properties);
    }

    @Test
    @DisplayName("Access Token을 발급하고 파싱하면 subject·email·role이 일치한다")
    void generateAndParseAccessToken() {
        String token = jwtTokenProvider.generateAccessToken("abc123", "user@example.com", "WARD");

        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo("abc123");
        assertThat(jwtTokenProvider.getEmail(token)).isEqualTo("user@example.com");
        assertThat(jwtTokenProvider.getRole(token)).isEqualTo("WARD");
    }

    @Test
    @DisplayName("Refresh Token의 subject는 userId와 일치한다")
    void generateRefreshTokenHasUserIdAsSubject() {
        String token = jwtTokenProvider.generateRefreshToken("abc123");

        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo("abc123");
    }

    @Test
    @DisplayName("유효한 Access Token은 validateToken에서 true를 반환한다")
    void validateValidToken() {
        String token = jwtTokenProvider.generateAccessToken("abc123", "user@example.com", "WARD");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰은 EXPIRED_TOKEN 예외를 던진다")
    void expiredTokenThrows() {
        // 만료 시간을 음수로 설정해 즉시 만료되는 토큰 생성
        properties.setAccessTokenExpiration(-1L);
        String expired = jwtTokenProvider.generateAccessToken("abc123", "user@example.com", "WARD");

        assertThatThrownBy(() -> jwtTokenProvider.validateToken(expired))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXPIRED_TOKEN);
    }

    @Test
    @DisplayName("형식이 잘못된 토큰은 INVALID_TOKEN 예외를 던진다")
    void malformedTokenThrows() {
        assertThatThrownBy(() -> jwtTokenProvider.validateToken("not-a-valid-token"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("다른 secret으로 서명된 토큰은 INVALID_TOKEN 예외를 던진다")
    void wrongSignatureThrows() {
        String token = jwtTokenProvider.generateAccessToken("abc123", "user@example.com", "WARD");

        // 다른 secret을 가진 별도 provider로 검증 → 서명 불일치.
        // (signingKey는 provider별로 1회 캐싱되므로 동일 provider의 secret을 런타임 교체하지 않고 새 provider로 검증)
        JwtProperties other = new JwtProperties();
        other.setSecret("different-secret-key-at-least-256-bits-long-for-hmac-sha256");
        other.setAccessTokenExpiration(properties.getAccessTokenExpiration());
        other.setRefreshTokenExpiration(properties.getRefreshTokenExpiration());
        JwtTokenProvider otherProvider = new JwtTokenProvider(other);

        assertThatThrownBy(() -> otherProvider.validateToken(token))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("Access Token은 isAccessToken=true, Refresh Token은 false — 토큰 타입 혼용 차단 (A-H1)")
    void accessTokenAndRefreshTokenAreDistinguishedByType() {
        String access = jwtTokenProvider.generateAccessToken("abc123", "user@example.com", "WARD");
        String refresh = jwtTokenProvider.generateRefreshToken("abc123");

        assertThat(jwtTokenProvider.isAccessToken(access)).isTrue();
        assertThat(jwtTokenProvider.isAccessToken(refresh)).isFalse();
    }

    @Test
    @DisplayName("getRemainingExpiration은 양수를 반환한다 (유효 토큰 기준)")
    void remainingExpirationPositive() {
        String token = jwtTokenProvider.generateAccessToken("abc123", "user@example.com", "WARD");

        long remaining = jwtTokenProvider.getRemainingExpiration(token);
        assertThat(remaining).isPositive();
        assertThat(remaining).isLessThanOrEqualTo(properties.getAccessTokenExpiration());
    }
}
