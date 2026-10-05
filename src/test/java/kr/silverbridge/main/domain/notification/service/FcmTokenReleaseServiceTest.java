package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.jwt.JwtProperties;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 세션 만료 뒤 이 기기 FCM 토큰 해제 (XAREA-G01).
 *
 * <p>JWT는 실제 {@link JwtTokenProvider}로 만든다 - 서명·만료 판정이 이 기능의 핵심이라 목으로 대신하면 의미가 없다.</p>
 */
@ExtendWith(MockitoExtension.class)
class FcmTokenReleaseServiceTest {

    private static final long ONE_WEEK = 7 * 24 * 60 * 60 * 1000L;

    @Mock private FcmService fcmService;

    private JwtProperties properties;
    private JwtTokenProvider jwtTokenProvider;
    private FcmTokenReleaseService service;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret("test-secret-key-at-least-256-bits-long-for-hmac-sha256-algorithm");
        properties.setAccessTokenExpiration(-60_000L); // 이미 만료된 토큰을 만든다
        properties.setRefreshTokenExpiration(ONE_WEEK);
        jwtTokenProvider = new JwtTokenProvider(properties);
        service = new FcmTokenReleaseService(fcmService, jwtTokenProvider, properties);
    }

    @Test
    @DisplayName("만료된 access token이어도 서명이 맞으면 그 주인 소유 토큰만 지운다 - 본인 소유 삭제 원칙(L-S2-3) 유지")
    void 만료_토큰으로_본인_토큰_해제() {
        String expired = jwtTokenProvider.generateAccessToken("GD0001", "g@example.com", "GUARDIAN");

        service.release(expired, "fcm-device-token");

        verify(fcmService).deleteToken("GD0001", "fcm-device-token");
    }

    @Test
    @DisplayName("refresh 수명보다 오래 전에 만료된 토큰은 401 - 그 세션이 살아 있었을 수 없는 시점")
    void 너무_오래된_토큰은_거절() {
        properties.setAccessTokenExpiration(-(ONE_WEEK + 60_000L));
        String old = jwtTokenProvider.generateAccessToken("GD0001", "g@example.com", "GUARDIAN");

        assertThatThrownBy(() -> service.release(old, "fcm-device-token"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
        verify(fcmService, never()).deleteToken(anyString(), anyString());
    }

    @Test
    @DisplayName("위조·refresh·깨진 토큰은 401이고 아무것도 지우지 않는다")
    void 위조_토큰은_거절() {
        JwtProperties other = new JwtProperties();
        other.setSecret("another-secret-key-at-least-256-bits-long-for-hmac-sha256-xx");
        other.setAccessTokenExpiration(-60_000L);
        other.setRefreshTokenExpiration(ONE_WEEK);
        String forged = new JwtTokenProvider(other).generateAccessToken("GD0001", "g@example.com", "GUARDIAN");
        String refresh = jwtTokenProvider.generateRefreshToken("GD0001");

        for (String bad : new String[]{forged, refresh, "garbage"}) {
            assertThatThrownBy(() -> service.release(bad, "fcm-device-token"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
        }
        verify(fcmService, never()).deleteToken(anyString(), anyString());
    }
}
