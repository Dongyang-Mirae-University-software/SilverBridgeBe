package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.EmailCheckRequest;
import kr.silverbridge.main.domain.auth.dto.FindEmailRequest;
import kr.silverbridge.main.domain.auth.dto.FindEmailResponse;
import kr.silverbridge.main.domain.auth.dto.LoginRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.dto.RegisterRequest;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshRequest;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshResponse;
import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import kr.silverbridge.main.global.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessLogService accessLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;

    // 이메일 중복 확인 (회원가입 전 단계)
    @Transactional(readOnly = true)
    public void checkEmail(EmailCheckRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    // 회원가입
    // 이메일/전화번호 중복 확인 → SMS 인증 완료 여부 확인 → 비밀번호 암호화 → UUID로 사용자 생성
    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 전화번호 중복 확인
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new CustomException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        // ADMIN 역할은 회원가입으로 선택 불가
        if (request.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }

        // SMS 인증 완료 여부 확인
        if (Boolean.FALSE.equals(redisTemplate.hasKey(RedisKeys.SMS_VERIFIED + request.getPhone()))) {
            throw new CustomException(ErrorCode.SMS_NOT_VERIFIED);
        }

        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .phone(request.getPhone())
                .role(request.getRole())
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .build();

        userRepository.save(user);

        // 회원가입 완료 후 인증 완료 키 삭제
        redisTemplate.delete(RedisKeys.SMS_VERIFIED + request.getPhone());
    }

    private static final int  LOGIN_MAX_ATTEMPTS = 5;   // 최대 로그인 실패 횟수
    private static final long LOGIN_LOCK_TTL     = 30L; // 잠금 유지 시간 (분)

    // 로그인
    // 잠금 확인 → 사용자 조회 → 계정 상태 검증 → 비밀번호 검증 → 토큰 발급 → Refresh Token 저장 → 로그 기록
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email    = request.getEmail();
        String lockKey  = RedisKeys.LOGIN_LOCK + email;
        String failKey  = RedisKeys.LOGIN_FAIL + email;

        // 잠금 상태 확인 (5회 실패 시 30분 잠금)
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getStatus() == Status.INACTIVE) {
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // 실패 횟수 증가
            Long attempts = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, LOGIN_LOCK_TTL, TimeUnit.MINUTES);

            // 5회 이상 실패 시 잠금 설정
            if (attempts != null && attempts >= LOGIN_MAX_ATTEMPTS) {
                redisTemplate.delete(failKey);
                redisTemplate.opsForValue().set(lockKey, "1", LOGIN_LOCK_TTL, TimeUnit.MINUTES);
            }

            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        // 로그인 성공 시 실패 횟수 초기화
        redisTemplate.delete(failKey);

        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // 기존 Refresh Token 삭제 후 새로 저장 (단일 디바이스 정책)
        refreshTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .token(refreshToken)
                .expiresAt(OffsetDateTime.now().plusSeconds(
                        jwtTokenProvider.getRemainingExpiration(refreshToken) / 1000))
                .build());

        user.updateLastLoginAt();
        accessLogService.log(user.getId(), AccessAction.LOGIN, ipAddress, userAgent);

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
                    .set(RedisKeys.LOGOUT_TOKEN + accessToken, "true", remaining, TimeUnit.MILLISECONDS);
        }
        refreshTokenRepository.deleteByUserId(userId);
        accessLogService.log(userId, AccessAction.LOGOUT, ipAddress, userAgent);
    }

    // Access Token 재발급
    // DB에서 Refresh Token 검증 → 만료 확인 → 새 Access Token 발급
    @Transactional
    public TokenRefreshResponse refresh(TokenRefreshRequest request) {
        RefreshToken savedToken = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));

        if (savedToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            refreshTokenRepository.delete(savedToken);
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        }

        User user = userRepository.findById(savedToken.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        String newAccessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name());

        accessLogService.log(user.getId(), AccessAction.TOKEN_ISSUE);

        return new TokenRefreshResponse(newAccessToken);
    }

    // 아이디(이메일) 찾기
    // 이름 + 전화번호로 사용자 조회 → 이메일 앞부분 마스킹 후 반환
    @Transactional(readOnly = true)
    public FindEmailResponse findEmail(FindEmailRequest request) {
        User user = userRepository.findByNameAndPhone(request.getName(), request.getPhone())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return new FindEmailResponse(maskEmail(user.getEmail()));
    }

    // 이메일 마스킹 처리
    // 예: username@example.com → us***me@example.com  (5자 이상: 앞 2자 + *** + 뒤 2자)
    //     user@example.com    → u***r@example.com     (3~4자: 앞 1자 + *** + 뒤 1자)
    //     ab@example.com      → a***@example.com      (2자 이하: 앞 1자 + ***)
    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);   // @ 앞부분
        String domain = email.substring(atIndex);      // @ 포함 뒷부분

        if (local.length() >= 5) {
            return local.substring(0, 2) + "***" + local.substring(local.length() - 2) + domain;
        } else if (local.length() >= 3) {
            return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
        } else {
            return local.charAt(0) + "***" + domain;
        }
    }
}
