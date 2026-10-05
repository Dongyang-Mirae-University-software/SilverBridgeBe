package kr.silverbridge.main.global.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    // 토큰 용도 구분 클레임 — refresh token을 access token처럼 사용하는 혼용을 차단 (A-H1)
    static final String CLAIM_TYPE = "typ";
    static final String TYPE_ACCESS = "access";
    static final String TYPE_REFRESH = "refresh";

    // secret → SecretKey 변환 결과를 1회만 계산해 캐싱 (매 토큰 연산마다 재생성 방지, D-3)
    // 멱등 계산이라 동시 초기화돼도 안전.
    private volatile SecretKey signingKey;

    // application.yaml의 secret 문자열을 SecretKey 객체로 변환 (32바이트 이상 필요)
    private SecretKey getSigningKey() {
        SecretKey key = signingKey;
        if (key == null) {
            key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
            signingKey = key;
        }
        return key;
    }

    // 로그아웃 시 Redis blacklist TTL 계산용 — 토큰 남은 유효 시간(ms) 반환
    public long getRemainingExpiration(String token) {
        Date expiration = getClaims(token).getExpiration();
        return expiration.getTime() - System.currentTimeMillis();
    }

    // 토큰의 SHA-256 해시(hex) — 로그아웃 블랙리스트 Redis 키로 사용
    // 토큰 원문을 키로 쓰지 않아 Redis 메모리 절약 + 원문 비노출
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    // 토큰 발급 시각(epoch ms) — 비밀번호 변경 후 무효화 비교에 사용
    public long getIssuedAt(String token) {
        return getClaims(token).getIssuedAt().getTime();
    }

    // Access Token 생성
    // subject: userId, claims에 email과 role 포함
    public String generateAccessToken(String userId, String email, String role) {
        return buildToken(userId, email, role, jwtProperties.getAccessTokenExpiration());
    }

    // Refresh Token 생성
    // Access Token과 달리 최소 정보(userId)만 담음
    // jti(UUID)를 넣어 같은 초·같은 userId라도 토큰이 매번 달라지게 한다 (AUTH-G04).
    // iat/exp가 초 단위라 jti가 없으면 1초 안의 재로그인·재발급이 바이트까지 같은 토큰을 만들어
    // refresh_tokens.token UNIQUE에 걸려 409가 났다.
    public String generateRefreshToken(String userId) {
        return Jwts.builder()
                .subject(userId)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtProperties.getRefreshTokenExpiration()))
                .signWith(getSigningKey())
                .compact();
    }

    // 토큰에서 userId 추출
    public String getUserId(String token) {
        return getClaims(token).getSubject();
    }

    // 토큰에서 email 추출
    public String getEmail(String token) {
        return getClaims(token).get("email", String.class);
    }

    // 토큰에서 role 추출
    public String getRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    // access token 여부 — 인증 필터에서 refresh token의 Bearer 사용을 차단하는 데 사용 (A-H1)
    // typ 클레임이 없는 과거 토큰은 access로 보지 않는다(false) → 배포 후 자연 재발급으로 전환.
    public boolean isAccessToken(String token) {
        return TYPE_ACCESS.equals(getClaims(token).get(CLAIM_TYPE, String.class));
    }

    // refresh token 여부 — 재발급·재사용 감지가 다른 종류의 토큰으로 세션을 폐기하지 않게 한다 (AUTH-G27)
    // typ 클레임이 없는 과거 토큰도 refresh로 보지 않는다(false).
    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(getClaims(token).get(CLAIM_TYPE, String.class));
    }

    // 토큰 유효성 검증
    // 만료, 변조, 형식 오류를 구분해서 로깅
    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("만료된 JWT 토큰: {}", e.getMessage());
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("유효하지 않은 JWT 토큰: {}", e.getMessage());
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
    }

    private String buildToken(String userId, String email, String role, long expiration) {
        // jti - 같은 초에 다시 로그인해도 access 토큰이 바이트까지 같아지지 않게 한다. 같으면 직전 로그아웃의
        // 블랙리스트(토큰 해시 키)에 새 토큰까지 걸렸다(refresh의 AUTH-G04와 같은 이유).
        return Jwts.builder()
                .subject(userId)
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("role", role)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 만료된 것까지 포함해 <b>우리가 서명한 access 토큰</b>의 사용자 ID를 꺼낸다(XAREA-G01).
     *
     * <p>자동 로그아웃(세션 만료) 뒤 그 기기의 FCM 토큰을 지우는 데<b>만</b> 쓴다. 세션이 끝나 유효한 토큰이 없는데도
     * "이 기기 알림을 꺼 달라"는 요청이 본인 것인지는 확인해야 해서, 서명은 검사하고 만료만 눈감는다.
     * 인증 수단으로 쓰지 말 것 - 만료·로그아웃·무효화된 토큰도 통과한다. 할 수 있는 일을 줄이는(본인 기기 알림 해제)
     * 용도라서만 허용한다.</p>
     *
     * <p>jjwt는 서명을 먼저 검증하고 만료를 나중에 본다 - 서명이 틀린 토큰은 만료 여부와 무관하게 거절된다.</p>
     *
     * @param maxExpiredMillis 만료 뒤 이 시간이 지난 토큰은 받지 않는다(오래 전 유출된 토큰의 재사용 범위를 좁힌다)
     * @return 서명이 맞고 typ=access이며 만료 허용 범위 안이면 사용자 ID, 아니면 빈 값
     */
    public Optional<String> getAccessTokenSubjectAllowingExpired(String token, long maxExpiredMillis) {
        Claims claims;
        try {
            claims = getClaims(token);
        } catch (ExpiredJwtException e) {
            claims = e.getClaims();
            Date expiration = claims.getExpiration();
            if (expiration == null || System.currentTimeMillis() - expiration.getTime() > maxExpiredMillis) {
                return Optional.empty();
            }
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            return Optional.empty();
        }
        return Optional.ofNullable(claims.getSubject());
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
