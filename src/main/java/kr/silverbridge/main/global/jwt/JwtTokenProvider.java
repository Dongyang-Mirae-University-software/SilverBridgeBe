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
    public String generateRefreshToken(String userId) {
        return Jwts.builder()
                .subject(userId)
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
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())
                .compact();
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
