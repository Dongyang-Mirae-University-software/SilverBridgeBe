package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 로그인으로 밀려난 refresh token을 회전 재사용(도난)과 구분한다 (AUTH-G03, 결정 D3).
 * <p>
 * 단일 기기 정책상 로그인은 그 사용자의 기존 refresh를 지운다. 밀려난 기기가 그 토큰으로 갱신을 시도하면 DB에 없는
 * 토큰이라 재사용 감지(H-3)로 가서 <b>방금 로그인한 새 기기의 토큰까지</b> 폐기했다 - 기기·탭 두 개를 쓰면 서로를 로그아웃시켰다.
 * <p>
 * 로그인할 때 새로 발급한 refresh의 iat(초)를 {@code refresh:login-at:{userId}}에 남기고, DB에 없는 refresh의 iat가
 * 그보다 이르면 "로그인으로 밀려난 토큰"으로 보고 재사용 감지 없이 401만 준다. 회전(refresh)으로 지워진 토큰은 그
 * 로그인 이후에 발급된 것이므로 기존대로 재사용 감지를 탄다.
 * <p>
 * 설계 메모: 지시안은 "밀려난 토큰 해시마다 마커"였으나, 지울 토큰 값을 읽으려면 RefreshTokenRepository에 조회 메서드가
 * 필요해(이 묶음 소유 밖) 사용자당 시각 하나로 같은 구분을 한다. 로그인 이전에 발급된 refresh는 어차피 그 로그인이 모두
 * 지웠으므로, 그 토큰을 쥔 쪽이 공격자든 본인이든 새 로그인 세션을 폐기해 얻는 보안 이득이 없다.
 * <p>
 * Redis 장애는 삼킨다(기록 실패 = 기존 동작으로 돌아갈 뿐, 로그인·갱신을 막지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginSupersedeMarker {

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    /** 로그인으로 refresh를 새로 발급한 직후 호출 - 그 토큰의 iat(초)를 기록한다. */
    public void markLogin(String userId, String newRefreshToken) {
        try {
            long ttlMs = jwtTokenProvider.getRemainingExpiration(newRefreshToken);
            if (ttlMs <= 0) {
                return;
            }
            long issuedAtSec = jwtTokenProvider.getIssuedAt(newRefreshToken) / 1000;
            redisTemplate.opsForValue().set(RedisKeys.REFRESH_LOGIN_AT + userId,
                    String.valueOf(issuedAtSec), ttlMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("[REFRESH-LOGIN-MARK-FAILED] userId={} cause={}", userId, e.getClass().getSimpleName());
        }
    }

    /** DB에 없는 refresh가 마지막 로그인보다 먼저 발급된 것인지. 기록이 없거나 읽기 실패면 false(기존 재사용 감지로). */
    public boolean isIssuedBeforeLastLogin(String userId, String refreshToken) {
        try {
            String value = redisTemplate.opsForValue().get(RedisKeys.REFRESH_LOGIN_AT + userId);
            if (value == null) {
                return false;
            }
            long lastLoginSec = Long.parseLong(value);
            return jwtTokenProvider.getIssuedAt(refreshToken) / 1000 < lastLoginSec;
        } catch (RuntimeException e) {
            log.warn("[REFRESH-LOGIN-CHECK-FAILED] userId={} cause={}", userId, e.getClass().getSimpleName());
            return false;
        }
    }
}
