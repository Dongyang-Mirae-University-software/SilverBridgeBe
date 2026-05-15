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
import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    // application.yaml의 secret 문자열을 SecretKey 객체로 변환 (32바이트 이상 필요)
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    // 로그아웃 시 Redis blacklist TTL 계산용 — 토큰 남은 유효 시간(ms) 반환
    public long getRemainingExpiration(String token) {
        Date expiration = getClaims(token).getExpiration();
        return expiration.getTime() - System.currentTimeMillis();
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
