package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.EmailCheckRequest;
import kr.silverbridge.main.domain.auth.dto.FindEmailRequest;
import kr.silverbridge.main.domain.auth.dto.FindEmailResponse;
import kr.silverbridge.main.domain.auth.dto.LoginRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.dto.RegisterRequest;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshRequest;
import kr.silverbridge.main.domain.auth.dto.TokenRefreshResponse;
import kr.silverbridge.main.domain.auth.config.AuthLoginProperties;
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
import kr.silverbridge.main.global.util.MaskingUtil;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.global.util.UserIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevocationService refreshTokenRevocationService;
    private final AccessLogService accessLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final UserIdGenerator userIdGenerator;
    private final SmsService smsService;
    private final AuthLoginProperties authLoginProperties;

    // 이메일 중복 확인 (회원가입 전 단계)
    @Transactional(readOnly = true)
    public void checkEmail(EmailCheckRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    // 회원가입
    // 이메일/전화번호 중복 확인 → SMS 인증 완료 여부 확인 → 비밀번호 암호화 → 6자 ID로 사용자 생성
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

        // SMS 인증 nonce 일치 확인 + 키 소비 (H-5)
        smsService.consumeVerification(request.getPhone(), request.getVerificationNonce());

        User user = User.builder()
                .id(userIdGenerator.generate())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .phone(request.getPhone())
                .role(request.getRole())
                .status(Status.ACTIVE)
                .provider(Provider.LOCAL)
                .gender(request.getGender())
                .birthDate(request.getBirthDate())
                .postcode(request.getPostcode())
                .address(request.getAddress())
                .addressDetail(request.getAddressDetail())
                .build();

        userRepository.save(user);
    }

    // 로그인
    // 사용자 조회 → 잠금 확인(user.id 기반) → 비밀번호 검증 → 계정 상태 검증 → 토큰 발급 → Refresh Token 저장 → 로그 기록
    // - 잠금 키는 user.id 기반(H-2): 임의 이메일로 정상 사용자를 잠그는 DoS 차단
    // - 가입 안 된 이메일과 비밀번호 불일치는 모두 INVALID_CREDENTIALS로 통합(H-1): 계정 enumeration 차단
    // - INACTIVE 안내는 비밀번호 검증 통과 이후에만 노출 — 본인만 정지 사실을 확인
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = request.getEmail();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        String lockKey = RedisKeys.LOGIN_LOCK + user.getId();
        String failKey = RedisKeys.LOGIN_FAIL + user.getId();

        // 잠금 상태 확인 (5회 실패 시 30분 잠금)
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            long lockTtl = authLoginProperties.getLockTtlMinutes();
            // 실패 횟수 증가
            Long attempts = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, lockTtl, TimeUnit.MINUTES);

            // 최대 실패 횟수 초과 시 잠금 설정
            if (attempts != null && attempts >= authLoginProperties.getMaxAttempts()) {
                redisTemplate.delete(failKey);
                redisTemplate.opsForValue().set(lockKey, "1", lockTtl, TimeUnit.MINUTES);
            }

            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 비밀번호 검증 통과 후 계정 상태 확인 (본인에게만 정지 사실 노출)
        if (user.getStatus() == Status.INACTIVE) {
            // 정지 계정 차단 시 남아있는 refresh token 정리 (refresh 메서드와 일관성 유지)
            // REQUIRES_NEW로 분리 — 아래 throw 시 본 트랜잭션 롤백돼도 폐기는 유지
            refreshTokenRevocationService.revokeAll(user.getId());
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        // 로그인 성공 시 실패 횟수 초기화
        redisTemplate.delete(failKey);

        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // 기존 Refresh Token 삭제 후 새로 저장 (단일 디바이스 정책)
        refreshTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.save(RefreshToken.of(user.getId(), refreshToken,
                jwtTokenProvider.getRemainingExpiration(refreshToken)));

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
            // 토큰 원문 대신 SHA-256 해시를 키로 사용 (Redis 메모리 절약 + 원문 비노출)
            redisTemplate.opsForValue()
                    .set(RedisKeys.LOGOUT_TOKEN + jwtTokenProvider.hashToken(accessToken),
                            "true", remaining, TimeUnit.MILLISECONDS);
        }
        refreshTokenRepository.deleteByUserId(userId);
        accessLogService.log(userId, AccessAction.LOGOUT, ipAddress, userAgent);
    }

    // Access Token 재발급
    // DB에서 Refresh Token 검증 → 만료 확인 → 새 Access Token + Refresh Token 발급 (Rotation)
    // 기존 Refresh Token은 즉시 무효화 → 탈취된 토큰 재사용 차단
    // 폐기된 옛 토큰이 다시 들어왔는데 같은 사용자에게 다른 token이 남아있다면 도난 신호로 간주 → 사용자의 모든 token 강제 폐기 (H-3)
    @Transactional
    public TokenRefreshResponse refresh(TokenRefreshRequest request) {
        Optional<RefreshToken> opt = refreshTokenRepository.findByToken(request.getRefreshToken());
        if (opt.isEmpty()) {
            detectAndHandleReuse(request.getRefreshToken());
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        RefreshToken savedToken = opt.get();

        if (savedToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
            // REQUIRES_NEW로 분리 — 아래 throw 시 본 트랜잭션 롤백돼도 폐기는 유지
            refreshTokenRevocationService.revokeOne(savedToken);
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        }

        User user = userRepository.findById(savedToken.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 비활성화된 계정은 토큰 재발급 차단 (탈퇴 또는 관리자 제한 계정)
        if (user.getStatus() == Status.INACTIVE) {
            // REQUIRES_NEW로 분리 — 아래 throw 시 본 트랜잭션 롤백돼도 폐기는 유지
            refreshTokenRevocationService.revokeOne(savedToken);
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        String newAccessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // 기존 Refresh Token 무효화 후 새 토큰 저장 (Rotation)
        refreshTokenRepository.delete(savedToken);
        refreshTokenRepository.save(RefreshToken.of(user.getId(), newRefreshToken,
                jwtTokenProvider.getRemainingExpiration(newRefreshToken)));

        accessLogService.log(user.getId(), AccessAction.TOKEN_ISSUE);

        return new TokenRefreshResponse(newAccessToken, newRefreshToken);
    }

    // 아이디(이메일) 찾기
    // 이름 + 전화번호로 계정 전체 조회
    // - LOCAL 계정: 마스킹된 이메일 반환
    // - KAKAO 계정: hasKakaoAccount=true 반환
    // - 둘 다 존재하면 둘 다 반환, 아무것도 없으면 USER_NOT_FOUND
    @Transactional(readOnly = true)
    public FindEmailResponse findEmail(FindEmailRequest request) {
        List<User> users = userRepository.findAllByNameAndPhone(request.getName(), request.getPhone());

        if (users.isEmpty()) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        // LOCAL 계정 1건을 잡아 마스킹 이메일과 가입일(yyyy-MM-dd)을 함께 산출
        Optional<User> localUser = users.stream()
                .filter(User::isLocalProvider)
                .findFirst();

        String maskedEmail = localUser
                .map(u -> MaskingUtil.maskEmail(u.getEmail()))
                .orElse(null);

        java.time.LocalDate joinedAt = localUser
                .map(u -> u.getCreatedAt().toLocalDate())
                .orElse(null);

        boolean hasKakaoAccount = users.stream().anyMatch(User::isSocialProvider);

        return new FindEmailResponse(maskedEmail, hasKakaoAccount, joinedAt);
    }

    // Refresh Token 재사용(도난) 감지
    // - DB에 없는 token이 들어왔을 때 호출
    // - JWT 자체는 유효하고(만료/변조 아님), 같은 userId의 다른 token이 DB에 남아 있다면
    //   "누군가 이미 rotation을 가져가고 옛 token이 돌아온 상황"으로 보고 user의 모든 token 강제 폐기
    private void detectAndHandleReuse(String suspectedToken) {
        String userId;
        try {
            if (!jwtTokenProvider.validateToken(suspectedToken)) return;
            userId = jwtTokenProvider.getUserId(suspectedToken);
        } catch (CustomException ignored) {
            return;
        }
        if (refreshTokenRepository.existsByUserId(userId)) {
            // REQUIRES_NEW로 분리 — caller가 INVALID_TOKEN을 throw해 본 트랜잭션이 롤백돼도 폐기는 유지
            refreshTokenRevocationService.revokeAll(userId);
            accessLogService.log(userId, AccessAction.TOKEN_REUSE_DETECTED);
            log.warn("Refresh token 재사용 감지: userId={} — 사용자의 모든 token 강제 폐기", userId);
        }
    }
}
