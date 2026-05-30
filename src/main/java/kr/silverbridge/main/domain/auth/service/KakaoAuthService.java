package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.KakaoLoginRequest;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginResponse;
import kr.silverbridge.main.domain.auth.dto.KakaoRegisterRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import kr.silverbridge.main.domain.auth.event.KakaoRegisteredEvent;
import kr.silverbridge.main.domain.auth.oauth.KakaoOAuthClient;
import kr.silverbridge.main.domain.auth.oauth.KakaoTokenResponse;
import kr.silverbridge.main.domain.auth.oauth.KakaoUserInfoResponse;
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
import kr.silverbridge.main.global.util.UserIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class KakaoAuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevocationService refreshTokenRevocationService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccessLogService accessLogService;
    private final StringRedisTemplate redisTemplate;
    private final UserIdGenerator userIdGenerator;
    private final SmsService smsService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    // 카카오 신규 가입 세션(pending) 유지 시간(분). 카카오 로그인(kakaoLogin) 시점부터 카운트되며,
    // 시니어/4050 타겟이 실명·주소·전화번호 입력 + SMS 인증까지 마치는 4단계 가입을 여유 있게 끝낼 수 있도록 30분으로 둔다.
    // (access token 만료 30분과는 무관한 별개 값 — 이 키는 가입 완료 전 임시 세션용)
    private static final long KAKAO_PENDING_TTL = 30L;
    // 카카오가 이메일을 제공하지 않을 때 사용하는 대체 이메일 형식 (kakao_{id}@kakao.com)
    private static final String FALLBACK_EMAIL_PREFIX = "kakao_";
    private static final String FALLBACK_EMAIL_DOMAIN = "@kakao.com";

    // 카카오 로그인
    // 기존 사용자 → 바로 로그인
    // 신규 사용자 → DB 저장 없이 카카오 정보만 반환 (Redis에 임시 저장)
    @Transactional
    public KakaoLoginResponse kakaoLogin(KakaoLoginRequest request, String ipAddress, String userAgent) {
        KakaoTokenResponse kakaoToken = kakaoOAuthClient.getToken(request.getCode(), redirectUri);
        KakaoUserInfoResponse kakaoUser = kakaoOAuthClient.getUserInfo(kakaoToken.getAccessToken());

        String kakaoId = String.valueOf(kakaoUser.getId());

        // 기존 카카오 사용자 → 바로 로그인
        return userRepository.findByProviderAndProviderId(Provider.KAKAO, kakaoId)
                .map(user -> {
                    if (user.getStatus() == Status.INACTIVE) {
                        // 정지 계정 차단 시 남아있는 refresh token 정리 (AuthService.refresh와 일관성 유지)
                        // REQUIRES_NEW로 분리 — 아래 throw 시 본 트랜잭션 롤백돼도 폐기는 유지
                        refreshTokenRevocationService.revokeAll(user.getId());
                        throw new CustomException(ErrorCode.INACTIVE_USER);
                    }
                    String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
                    String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

                    refreshTokenRepository.deleteByUserId(user.getId());
                    refreshTokenRepository.save(RefreshToken.of(user.getId(), refreshToken,
                            jwtTokenProvider.getRemainingExpiration(refreshToken)));

                    user.updateLastLoginAt();
                    accessLogService.log(user.getId(), AccessAction.KAKAO_LOGIN, ipAddress, userAgent);

                    return KakaoLoginResponse.ofExisting(user, accessToken, refreshToken);
                })
                .orElseGet(() -> {
                    // 신규 사용자 → 이메일 처리
                    String email = kakaoUser.getEmail();
                    if (email == null || email.isBlank()) {
                        email = FALLBACK_EMAIL_PREFIX + kakaoId + FALLBACK_EMAIL_DOMAIN;
                    }

                    // 동일 이메일로 이미 LOCAL 가입된 계정이 있으면 예외
                    if (userRepository.existsByEmail(email)) {
                        throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
                    }

                    // 카카오 닉네임은 사용하지 않는다. 회원가입 시 사용자가 본인 실명을 직접 입력하도록
                    // name은 프리필하지 않고 null로 반환한다.

                    // Redis에 카카오 정보 임시 저장 (TTL 30분 — KAKAO_PENDING_TTL)
                    redisTemplate.opsForValue()
                            .set(RedisKeys.KAKAO_PENDING + kakaoId, email, KAKAO_PENDING_TTL, TimeUnit.MINUTES);

                    return KakaoLoginResponse.ofNewUser(kakaoId, email, null, kakaoUser.getProfileImageUrl());
                });
    }

    // 카카오 신규 회원가입 완료
    // 카카오 세션 확인 → 중복 검사 → SMS 인증 소비 → DB 저장 → 토큰 발급
    @Transactional
    public LoginResponse kakaoRegister(KakaoRegisterRequest request, String ipAddress, String userAgent) {
        String kakaoId = request.getKakaoId();

        // 카카오 세션 확인 (이메일 위변조 방지)
        String pendingKey = RedisKeys.KAKAO_PENDING + kakaoId;
        String email = redisTemplate.opsForValue().get(pendingKey);
        if (email == null) {
            throw new CustomException(ErrorCode.KAKAO_SESSION_EXPIRED);
        }

        // ADMIN 역할 선택 불가
        if (request.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }

        // 이메일 중복 확인 (재검증)
        if (userRepository.existsByEmail(email)) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 전화번호 중복 확인
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new CustomException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        // SMS 인증 nonce 일치 확인 + 키 소비 (H-5)
        // 위의 모든 검증(세션 만료·역할·중복)을 통과한 뒤 맨 마지막에 소비한다.
        // consumeVerification은 Redis 키를 즉시 삭제하는데 이 삭제는 @Transactional 롤백 대상이 아니므로,
        // 검증보다 먼저 소비하면 검증 실패 시 nonce가 비가역적으로 소모돼 재시도가 막힌다.
        // LOCAL AuthService.register와 동일하게 "검증 후 마지막 소비" 순서를 맞춘다.
        smsService.consumeVerification(request.getPhone(), request.getVerificationNonce());

        // 카카오 사용자 DB 저장
        User user = User.builder()
                .id(userIdGenerator.generate())
                .email(email)
                .password(null)
                .name(request.getName())
                .phone(request.getPhone())
                .role(request.getRole())
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .providerId(kakaoId)
                .profileImage(request.getProfileImageUrl())
                .gender(request.getGender())
                .birthDate(request.getBirthDate())
                .postcode(request.getPostcode())
                .address(request.getAddress())
                .addressDetail(request.getAddressDetail())
                .build();

        userRepository.save(user);

        // Redis 키 삭제 (SMS 인증 키는 consumeVerification에서 이미 소비됨)
        redisTemplate.delete(pendingKey);

        // 토큰 발급
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), email, user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        refreshTokenRepository.save(RefreshToken.of(user.getId(), refreshToken,
                jwtTokenProvider.getRemainingExpiration(refreshToken)));

        user.updateLastLoginAt();

        // KAKAO_LOGIN 접속로그는 가입 트랜잭션 커밋 후(AFTER_COMMIT)에 기록한다.
        // 여기서 accessLogService.log()(REQUIRES_NEW)를 직접 호출하면, 아직 커밋되지 않은 users 행을
        // 별도 트랜잭션이 보지 못해 FK 위반(SQLState 23503)이 발생한다.
        // (일반 가입 AuthService.register는 가입 시 접속로그를 남기지 않아 이 문제가 없었다.)
        eventPublisher.publishEvent(new KakaoRegisteredEvent(user.getId(), ipAddress, userAgent));

        return LoginResponse.of(user, accessToken, refreshToken);
    }
}
