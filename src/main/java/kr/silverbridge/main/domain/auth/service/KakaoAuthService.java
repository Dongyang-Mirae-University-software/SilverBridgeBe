package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.KakaoLoginRequest;
import kr.silverbridge.main.domain.auth.dto.KakaoLoginResponse;
import kr.silverbridge.main.domain.auth.dto.KakaoRegisterRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.entity.RefreshToken;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class KakaoAuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccessLogService accessLogService;
    private final StringRedisTemplate redisTemplate;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    private static final long KAKAO_PENDING_TTL = 10L;

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
                        throw new CustomException(ErrorCode.INACTIVE_USER);
                    }
                    String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
                    String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

                    refreshTokenRepository.deleteByUserId(user.getId());
                    refreshTokenRepository.save(RefreshToken.builder()
                            .userId(user.getId())
                            .token(refreshToken)
                            .expiresAt(OffsetDateTime.now().plusSeconds(
                                    jwtTokenProvider.getRemainingExpiration(refreshToken) / 1000))
                            .build());

                    user.updateLastLoginAt();
                    accessLogService.log(user.getId(), AccessAction.KAKAO_LOGIN, ipAddress, userAgent);

                    return KakaoLoginResponse.ofExisting(user, accessToken, refreshToken);
                })
                .orElseGet(() -> {
                    // 신규 사용자 → 이메일 처리
                    String email = kakaoUser.getEmail();
                    if (email == null || email.isBlank()) {
                        email = "kakao_" + kakaoId + "@kakao.com";
                    }

                    // 동일 이메일로 이미 LOCAL 가입된 계정이 있으면 예외
                    if (userRepository.existsByEmail(email)) {
                        throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
                    }

                    String name = kakaoUser.getNickname();
                    if (name == null || name.isBlank()) {
                        name = "카카오사용자";
                    }

                    // Redis에 카카오 정보 임시 저장 (TTL 10분)
                    redisTemplate.opsForValue()
                            .set(RedisKeys.KAKAO_PENDING + kakaoId, email, KAKAO_PENDING_TTL, TimeUnit.MINUTES);

                    return KakaoLoginResponse.ofNewUser(kakaoId, email, name, kakaoUser.getProfileImageUrl());
                });
    }

    // 카카오 신규 회원가입 완료
    // SMS 인증 확인 → 카카오 세션 확인 → 중복 검사 → DB 저장 → 토큰 발급
    @Transactional
    public LoginResponse kakaoRegister(KakaoRegisterRequest request, String ipAddress, String userAgent) {
        String kakaoId = request.getKakaoId();

        // SMS 인증 완료 여부 확인
        if (Boolean.FALSE.equals(redisTemplate.hasKey(RedisKeys.SMS_VERIFIED + request.getPhone()))) {
            throw new CustomException(ErrorCode.SMS_NOT_VERIFIED);
        }

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

        // 카카오 사용자 DB 저장
        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .password(null)
                .name(request.getName())
                .phone(request.getPhone())
                .role(request.getRole())
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .providerId(kakaoId)
                .profileImage(request.getProfileImageUrl())
                .build();

        userRepository.save(user);

        // Redis 키 삭제
        redisTemplate.delete(pendingKey);
        redisTemplate.delete(RedisKeys.SMS_VERIFIED + request.getPhone());

        // 토큰 발급
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), email, user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .token(refreshToken)
                .expiresAt(OffsetDateTime.now().plusSeconds(
                        jwtTokenProvider.getRemainingExpiration(refreshToken) / 1000))
                .build());

        user.updateLastLoginAt();
        accessLogService.log(user.getId(), AccessAction.KAKAO_LOGIN, ipAddress, userAgent);

        return LoginResponse.of(user, accessToken, refreshToken);
    }
}
