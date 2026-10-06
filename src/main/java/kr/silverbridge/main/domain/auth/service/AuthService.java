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
import kr.silverbridge.main.global.util.RedisCounter;
import kr.silverbridge.main.global.util.RedisKeys;
import kr.silverbridge.main.domain.user.service.UserIdGenerator;
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
    private final RedisCounter redisCounter;
    private final UserIdGenerator userIdGenerator;
    private final SmsService smsService;
    private final AuthLoginProperties authLoginProperties;
    private final LoginSupersedeMarker loginSupersedeMarker;

    // 미가입 이메일 로그인에도 BCrypt 비교를 한 번 하기 위한 더미 해시 (AUTH-G25).
    // 같은 인코더(strength 12)로 만들어 가입 이메일의 오답과 응답 시간이 같아진다. 처음 쓸 때 한 번만 만든다.
    private volatile String dummyPasswordHash;

    // 이메일 중복 확인 (회원가입 전 단계)
    @Transactional(readOnly = true)
    public void checkEmail(EmailCheckRequest request) {
        if (userRepository.existsByEmail(AuthInputNormalizer.email(request.getEmail()))) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    // 회원가입
    // 이메일/전화번호 중복 확인 → SMS 인증 완료 여부 확인 → 비밀번호 암호화 → 6자 ID로 사용자 생성
    // 이메일은 소문자, 이름은 TextSanitizer로 정규화해 저장한다(AUTH-G09·AUTH-G13) - 모든 검증이 nonce 소비보다 앞이다.
    @Transactional
    public void register(RegisterRequest request) {
        String email = AuthInputNormalizer.email(request.getEmail());
        String name = AuthInputNormalizer.name(request.getName());

        // 카카오 대체 이메일(kakao_{숫자}@kakao.com)은 일반 가입으로 선점할 수 없다 (AUTH-G08).
        // DTO @Pattern이 "사용할 수 없는 이메일입니다."로 먼저 막고, 여기는 정규화 뒤 값에 대한 방어선이다.
        if (AuthInputNormalizer.isReservedKakaoEmail(email)) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }

        if (userRepository.existsByEmail(email)) {
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
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .name(name)
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
    // 사용자 조회 → 잠금 확인 → 시도 횟수 예약 → 비밀번호 검증 → 계정 상태 검증 → 토큰 발급 → Refresh Token 저장 → 로그 기록
    // - 잠금 키는 user.id 기반(H-2): 임의 이메일로 정상 사용자를 잠그는 DoS 차단
    // - 가입 안 된 이메일과 비밀번호 불일치는 모두 INVALID_CREDENTIALS로 통합(H-1): 계정 enumeration 차단
    // - 미가입 이메일도 같은 카운터(정규화 이메일의 해시 키)·더미 BCrypt 비교를 거친다(AUTH-G25) -
    //   6번째에 429가 되는 시점과 응답 시간이 가입 이메일과 같다. 해시 키는 user.id와 겹치지 않고, 나중에 그 이메일로
    //   가입해도 새 계정의 잠금(user.id 키)에는 영향이 없다.
    // - 시도 횟수는 비밀번호 비교 "전에" 원자적으로 예약한다(AUTH-G05). 비교(BCrypt 수백 ms) 뒤에 세면 동시 요청이 모두
    //   잠금 전 상태로 평가돼 5회를 넘겨 비교됐다. 예약 결과가 한도를 넘으면 비교 없이 429다 - 동시 요청이 몇 건이든
    //   한 윈도우에서 비교는 최대 maxAttempts건이다. Redis 장애 시 RedisCounter 예외가 그대로 나가 로그인을 막는다(fail-closed).
    // - INACTIVE 안내는 비밀번호 검증 통과 이후에만 노출 — 본인만 정지 사실을 확인
    // - 사용자 엔티티는 @DynamicUpdate라 이 트랜잭션은 last_login_at만 UPDATE한다(ADMIN-G04) - BCrypt 동안 관리자가 바꾼
    //   상태·역할·이름을 로그인 커밋이 옛 값으로 덮지 않는다.
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = AuthInputNormalizer.email(request.getEmail());

        User user = userRepository.findByEmail(email).orElse(null);

        String attemptSubject = user != null ? user.getId() : AuthInputNormalizer.sha256Hex(email);
        String lockKey = RedisKeys.LOGIN_LOCK + attemptSubject;
        String failKey = RedisKeys.LOGIN_FAIL + attemptSubject;
        long lockTtlMinutes = authLoginProperties.getLockTtlMinutes();
        int maxAttempts = authLoginProperties.getMaxAttempts();

        // 잠금 상태 확인 (5회 실패 시 30분 잠금)
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }

        // 시도 횟수 예약 - 증가 + 최초 1회 TTL을 원자적으로(M-4 패턴). 윈도우는 첫 시도 시각 기준 고정.
        // 성공하면 아래에서 지우므로 정상 사용자의 성공 시도는 누적되지 않는다.
        long attempts = redisCounter.incrementWithTtl(failKey, lockTtlMinutes * 60);
        if (attempts > maxAttempts) {
            // 한도를 넘긴 예약 - 비교하지 않는다. 잠금 키가 아직 없으면(동시 요청이 먼저 도착) 여기서 건다.
            redisTemplate.opsForValue().setIfAbsent(lockKey, "1", lockTtlMinutes, TimeUnit.MINUTES);
            throw new CustomException(ErrorCode.LOGIN_LOCKED);
        }

        String passwordHash = user != null ? user.getPassword() : dummyPasswordHash();
        boolean matched = passwordEncoder.matches(request.getPassword(), passwordHash);
        if (user == null || !matched) {
            // 최대 실패 횟수 도달 시 잠금 설정. 실패 카운터는 지우지 않는다 - 지우면 이미 잠금 검사를 통과한 동시 요청이
            // 1부터 다시 예약해 비교까지 가게 된다. 카운터는 자기 TTL(잠금과 같은 길이)로 사라진다.
            if (attempts >= maxAttempts) {
                redisTemplate.opsForValue().set(lockKey, "1", lockTtlMinutes, TimeUnit.MINUTES);
                // 보안 이벤트 기록 — 모니터링용. PII 없이 userId·시도횟수만 (E-3). 미가입 이메일은 식별자를 남기지 않는다.
                log.warn("로그인 연속 실패로 계정 잠금: userId={}, 실패 {}회 → {}분 잠금",
                        user != null ? user.getId() : "(미가입)", attempts, lockTtlMinutes);
            }
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 비밀번호가 맞았으면 실패 횟수 초기화 - 정지 계정도 본인 확인은 된 것이라 카운터를 남기지 않는다
        redisTemplate.delete(failKey);

        // 비밀번호 검증 통과 후 계정 상태 확인 (본인에게만 정지 사실 노출)
        // ACTIVE가 아닌 모든 상태를 막는다 - INACTIVE(탈퇴 진행) + RESTRICTED(관리자 정지).
        // INACTIVE 등호 비교로 두면 새 상태값이 늘 때마다 조용히 로그인이 뚫린다.
        if (user.getStatus() != Status.ACTIVE) {
            // 정지 계정 차단 시 남아있는 refresh token 정리 (refresh 메서드와 일관성 유지)
            // REQUIRES_NEW로 분리 — 아래 throw 시 본 트랜잭션 롤백돼도 폐기는 유지
            refreshTokenRevocationService.revokeAll(user.getId());
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // 기존 Refresh Token 삭제 후 새로 저장 (단일 디바이스 정책)
        // 밀려난 토큰이 갱신에 쓰이면 재사용 감지가 아니라 단순 401이 되도록 로그인 시각을 남긴다(AUTH-G03)
        refreshTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.save(RefreshToken.of(user.getId(), refreshToken,
                jwtTokenProvider.getRemainingExpiration(refreshToken)));
        loginSupersedeMarker.markLogin(user.getId(), refreshToken);

        user.updateLastLoginAt();
        accessLogService.log(user.getId(), AccessAction.LOGIN, ipAddress, userAgent);

        return LoginResponse.of(user, accessToken, refreshToken);
    }

    // 미가입 이메일 비교용 더미 BCrypt 해시 - 실제 인코더로 한 번 만들어 재사용한다(멱등이라 동시 초기화돼도 안전)
    private String dummyPasswordHash() {
        String hash = dummyPasswordHash;
        if (hash == null) {
            hash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
            dummyPasswordHash = hash;
        }
        return hash;
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
    // refresh 종류(typ)가 아닌 토큰은 재사용 감지 없이 INVALID_TOKEN만 (AUTH-G27).
    // 같은 초 재발급의 UNIQUE 충돌(AUTH-G04)은 refresh 토큰의 jti로 막는다 - 삭제 후 저장 순서에 flush는 필요 없다.
    @Transactional
    public TokenRefreshResponse refresh(TokenRefreshRequest request) {
        return refresh(request.getRefreshToken());
    }

    // 쿠키로 받은 토큰도 같은 경로를 탄다 - 회전·재사용 감지 규칙은 전달 방식과 무관하다 (XCUT-G31)
    @Transactional
    public TokenRefreshResponse refresh(String refreshTokenValue) {
        Optional<RefreshToken> opt = refreshTokenRepository.findByToken(refreshTokenValue);
        if (opt.isEmpty()) {
            detectAndHandleReuse(refreshTokenValue);
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

        // ACTIVE가 아닌 계정은 토큰 재발급 차단 (탈퇴 진행 중이거나 관리자가 정지시킨 계정)
        if (user.getStatus() != Status.ACTIVE) {
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
        // 가입과 같은 규칙으로 이름을 정규화해 조회한다 (AUTH-G13·FEUX-G11)
        List<User> users = userRepository.findAllByNameAndPhone(
                AuthInputNormalizer.name(request.getName()), request.getPhone());

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
            // refresh 토큰이 아니면(access 토큰을 잘못 보낸 경우 등) 재사용 신호가 아니다 - 아무것도 폐기하지 않는다 (AUTH-G27).
            // typ 없이 subject만 보면 access 토큰 한 번 잘못 보낸 것으로 정상 세션이 통째로 끊기고 TOKEN_REUSE_DETECTED가 오탐된다.
            if (!jwtTokenProvider.isRefreshToken(suspectedToken)) return;
            userId = jwtTokenProvider.getUserId(suspectedToken);
        } catch (CustomException ignored) {
            return;
        }
        // 마지막 로그인보다 먼저 발급된 토큰 = 다른 기기 로그인으로 밀려난 토큰이다(AUTH-G03, D3).
        // 그 로그인이 이미 이 토큰을 지웠으므로 재사용 신호가 아니다 - 새 기기 세션을 폐기하지 않고 401만 준다.
        // 회전(refresh)으로 지워진 토큰은 마지막 로그인 이후 발급분이라 아래 재사용 감지(H-3)를 그대로 탄다.
        if (loginSupersedeMarker.isIssuedBeforeLastLogin(userId, suspectedToken)) {
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
