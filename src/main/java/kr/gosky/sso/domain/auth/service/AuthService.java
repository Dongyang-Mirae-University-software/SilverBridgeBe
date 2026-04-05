package kr.gosky.sso.domain.auth.service;

import kr.gosky.sso.domain.auth.dto.LoginRequest;
import kr.gosky.sso.domain.auth.dto.LoginResponse;
import kr.gosky.sso.domain.auth.dto.RegisterRequest;
import kr.gosky.sso.domain.auth.dto.TokenRefreshRequest;
import kr.gosky.sso.domain.auth.dto.TokenRefreshResponse;
import kr.gosky.sso.domain.auth.entity.AccessLog;
import kr.gosky.sso.domain.auth.entity.RefreshToken;
import kr.gosky.sso.domain.auth.repository.AccessLogRepository;
import kr.gosky.sso.domain.auth.repository.RefreshTokenRepository;
import kr.gosky.sso.domain.user.entity.User;
import kr.gosky.sso.domain.user.repository.UserRepository;
import kr.gosky.sso.global.enums.Provider;
import kr.gosky.sso.global.enums.Role;
import kr.gosky.sso.global.enums.Status;
import kr.gosky.sso.global.exception.CustomException;
import kr.gosky.sso.global.exception.ErrorCode;
import kr.gosky.sso.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessLogRepository accessLogRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    // 회원가입
    // 이메일 중복 확인 → 비밀번호 암호화 → UUID로 사용자 생성
    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .phone(request.getPhone())
                .role(Role.USER)
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .emailVerified(false)
                .build();

        userRepository.save(user);
    }

    // 로그인
    // 사용자 조회 → 계정 상태/비밀번호 검증 → 토큰 발급 → Refresh Token 저장 → 로그 기록
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getStatus() == Status.INACTIVE) {
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // 기존 Refresh Token 삭제 후 새로 저장 (단일 디바이스 정책)
        refreshTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .token(refreshToken)
                .expiresAt(LocalDateTime.now().plusSeconds(
                        jwtTokenProvider.getRemainingExpiration(refreshToken) / 1000))
                .build());

        user.updateLastLoginAt();
        saveAccessLog(user.getId(), "LOGIN", ipAddress, userAgent);

        return LoginResponse.of(user, accessToken, refreshToken);
    }

    // 로그아웃
    // Access Token → Redis blacklist 등록 (남은 만료 시간만큼 TTL)
    // Refresh Token → DB에서 삭제
    @Transactional
    public void logout(String accessToken, String userId, String ipAddress, String userAgent) {
        long remaining = jwtTokenProvider.getRemainingExpiration(accessToken);
        if (remaining > 0) {
            redisTemplate.opsForValue()
                    .set("blacklist:" + accessToken, "logout", remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        refreshTokenRepository.deleteByUserId(userId);
        saveAccessLog(userId, "LOGOUT", ipAddress, userAgent);
    }

    // Access Token 재발급
    // DB에서 Refresh Token 검증 → 만료 확인 → 새 Access Token 발급
    @Transactional
    public TokenRefreshResponse refresh(TokenRefreshRequest request) {
        RefreshToken savedToken = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));

        if (savedToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(savedToken);
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        }

        User user = userRepository.findById(savedToken.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String newAccessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name());

        saveAccessLog(user.getId(), "TOKEN_ISSUE", null, null);

        return new TokenRefreshResponse(newAccessToken);
    }

    // 접속 로그 저장 공통 메서드
    protected void saveAccessLog(String userId, String action, String ipAddress, String userAgent) {
        accessLogRepository.save(AccessLog.builder()
                .userId(userId)
                .action(action)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build());
    }
}
