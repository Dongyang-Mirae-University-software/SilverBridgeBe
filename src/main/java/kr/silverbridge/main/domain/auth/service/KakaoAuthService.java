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
import kr.silverbridge.main.domain.user.service.UserIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Slf4j
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
    private final LoginSupersedeMarker loginSupersedeMarker;

    @Value("${kakao.redirect-uri}")
    private String redirectUri;

    // 카카오 신규 가입 세션(pending) 유지 시간(분). 카카오 로그인(kakaoLogin) 시점부터 카운트되며,
    // 시니어/4050 타겟이 실명·주소·전화번호 입력 + SMS 인증까지 마치는 4단계 가입을 여유 있게 끝낼 수 있도록 30분으로 둔다.
    // (access token 만료 30분과는 무관한 별개 값 — 이 키는 가입 완료 전 임시 세션용)
    private static final long KAKAO_PENDING_TTL = 30L;
    // 카카오가 이메일을 제공하지 않을 때 사용하는 대체 이메일 형식 (kakao_{id}@kakao.com)
    private static final String FALLBACK_EMAIL_PREFIX = "kakao_";
    private static final String FALLBACK_EMAIL_DOMAIN = "@kakao.com";

    // 카카오 가입 세션을 "시작한 본인"에게 묶는 일회용 토큰 (AUTH-G07).
    // kakaoId는 URL 쿼리·브라우저 기록으로 새어 나갈 수 있어, kakaoId만으로 남의 가입 세션을 자기 정보로 완료(선점)할 수 있었다.
    // 로그인 응답으로만 내려가는 추측 불가능한 값을 함께 요구한다. Redis에는 원문 대신 SHA-256만 둔다.
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PENDING_TOKEN_BYTES = 32;
    // pending 값 = "{pendingToken SHA-256 hex}|{email}". 해시는 64자 고정이라 첫 구분자로 나눈다.
    private static final char PENDING_VALUE_SEPARATOR = '|';

    // 프로필 이미지 주소 상한 - users.profile_image VARCHAR(500) (AUTH-G22)
    private static final int PROFILE_IMAGE_URL_MAX_LENGTH = 500;
    // 허용 호스트 - 카카오 CDN(kakaocdn.net)과 그 하위 도메인(k.kakaocdn.net, img1.kakaocdn.net 등)만 (결정 D6)
    private static final String KAKAO_CDN_DOMAIN = "kakaocdn.net";

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
                    // ACTIVE가 아닌 모든 상태를 막는다 (INACTIVE 탈퇴 진행 / RESTRICTED 관리자 정지)
                    if (user.getStatus() != Status.ACTIVE) {
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
                    // 밀려난 기기의 갱신은 재사용 감지가 아니라 단순 401로 (AUTH-G03)
                    loginSupersedeMarker.markLogin(user.getId(), refreshToken);

                    user.updateLastLoginAt();
                    accessLogService.log(user.getId(), AccessAction.KAKAO_LOGIN, ipAddress, userAgent);

                    return KakaoLoginResponse.ofExisting(user, accessToken, refreshToken);
                })
                .orElseGet(() -> {
                    // 신규 사용자 → 이메일 처리 (가입·로그인과 같은 trim+소문자 정규화, AUTH-G09)
                    String email = AuthInputNormalizer.email(kakaoUser.getEmail());
                    if (email == null || email.isBlank()) {
                        email = FALLBACK_EMAIL_PREFIX + kakaoId + FALLBACK_EMAIL_DOMAIN;
                    }

                    // 동일 이메일로 이미 가입된 계정이 있으면 예외 - 일반 가입 계정이면 기존 로그인 방법을 안내 (AUTH-G08)
                    if (userRepository.existsByEmail(email)) {
                        throw emailTaken(email);
                    }

                    // 카카오 닉네임은 사용하지 않는다. 회원가입 시 사용자가 본인 실명을 직접 입력하도록
                    // name은 프리필하지 않고 null로 반환한다.

                    // Redis에 카카오 정보 임시 저장 (TTL 30분 — KAKAO_PENDING_TTL)
                    // 가입 완료는 응답으로 받은 pendingToken을 함께 보내야 한다 (AUTH-G07). 다시 로그인하면 새 토큰으로 교체된다.
                    String pendingToken = generatePendingToken();
                    redisTemplate.opsForValue()
                            .set(RedisKeys.KAKAO_PENDING + kakaoId,
                                    AuthInputNormalizer.sha256Hex(pendingToken) + PENDING_VALUE_SEPARATOR + email,
                                    KAKAO_PENDING_TTL, TimeUnit.MINUTES);

                    // 카카오는 프로필 이미지를 http로 줄 때가 있다 - 가입에서 받는 https 주소로 올려 내려준다.
                    // 허용 범위 밖이면 이미지 없이 진행한다(로그인 자체를 막지 않는다).
                    String profileImageUrl = toAllowedProfileImageUrlOrNull(kakaoUser.getProfileImageUrl());

                    return KakaoLoginResponse.ofNewUser(kakaoId, email, null, profileImageUrl, pendingToken);
                });
    }

    // 카카오 신규 회원가입 완료
    // 카카오 세션·pendingToken 확인 → 입력 정규화·검증 → 중복 검사 → SMS 인증 소비 → DB 저장 → 토큰 발급
    // 모든 검증은 SMS nonce 소비보다 앞이다(검증 후 마지막 소비) - 이미지 주소·이름이 틀려 400이 나도 인증은 남는다.
    @Transactional
    public LoginResponse kakaoRegister(KakaoRegisterRequest request, String ipAddress, String userAgent) {
        String kakaoId = request.getKakaoId();

        // 카카오 세션 확인 (이메일 위변조 방지)
        String pendingKey = RedisKeys.KAKAO_PENDING + kakaoId;
        String pendingValue = redisTemplate.opsForValue().get(pendingKey);
        if (pendingValue == null) {
            throw new CustomException(ErrorCode.KAKAO_SESSION_EXPIRED);
        }

        // 세션을 시작한 본인인지 확인 (AUTH-G07) - 로그인 응답의 pendingToken과 대조한다.
        // 배포 전에 만들어진 옛 형식(이메일만 저장) 세션은 대조할 토큰이 없으므로 만료로 보고 카카오 로그인을 다시 하게 한다.
        int separator = pendingValue.indexOf(PENDING_VALUE_SEPARATOR);
        if (separator < 0) {
            throw new CustomException(ErrorCode.KAKAO_SESSION_EXPIRED);
        }
        String expectedTokenHash = pendingValue.substring(0, separator);
        String email = pendingValue.substring(separator + 1);
        String providedTokenHash = request.getPendingToken() == null
                ? "" : AuthInputNormalizer.sha256Hex(request.getPendingToken());
        if (!MessageDigest.isEqual(expectedTokenHash.getBytes(StandardCharsets.UTF_8),
                providedTokenHash.getBytes(StandardCharsets.UTF_8))) {
            log.warn("[KAKAO-PENDING-MISMATCH] 카카오 가입 세션 토큰 불일치 - 세션을 시작하지 않은 쪽의 완료 시도일 수 있음");
            throw new CustomException(ErrorCode.KAKAO_SESSION_EXPIRED);
        }

        // 이름 정규화(AUTH-G13) - 실제 글자가 없으면 400
        String name = AuthInputNormalizer.name(request.getName());

        // 프로필 이미지 주소 검증(AUTH-G22·USER-G03) - 카카오 CDN의 https 주소만 저장한다
        String profileImageUrl = toAllowedProfileImageUrl(request.getProfileImageUrl());

        // ADMIN 역할 선택 불가
        if (request.getRole() == Role.ADMIN) {
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }

        // 이메일 중복 확인 (재검증)
        if (userRepository.existsByEmail(email)) {
            throw emailTaken(email);
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
                .name(name)
                .phone(request.getPhone())
                .role(request.getRole())
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .providerId(kakaoId)
                .profileImage(profileImageUrl)
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

    /**
     * 이미 쓰이는 이메일로 카카오 가입을 시도했을 때의 응답 (AUTH-G08).
     * 일반(이메일/비밀번호) 가입 계정이면 {@code KAKAO_EMAIL_REGISTERED_LOCAL}로 기존 로그인 방법을 안내하고,
     * 그 밖(다른 카카오 계정 등)은 종전대로 {@code EMAIL_ALREADY_EXISTS}. 계정 연동은 하지 않는다.
     */
    private CustomException emailTaken(String email) {
        boolean local = userRepository.findByEmail(email)
                .map(User::isLocalProvider)
                .orElse(false);
        return new CustomException(local ? ErrorCode.KAKAO_EMAIL_REGISTERED_LOCAL : ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    private static String generatePendingToken() {
        byte[] bytes = new byte[PENDING_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 가입 요청의 프로필 이미지 주소 검증·정규화 (AUTH-G22·USER-G03, 결정 D6). 위반 시 400.
    // - 없으면(null·빈 값) 이미지 없이 가입
    // - 500자 이하, 사용자 정보·포트 없는 주소, 호스트가 kakaocdn.net 또는 그 하위 도메인만
    // - 카카오는 이미지를 http로 줄 때가 있어(secure_resource 미지정) 카카오 CDN이면 https로 올려 저장한다
    // 저장된 주소는 다른 사용자 화면의 <img>와 파일서버 삭제 경로로 쓰이므로 임의 주소를 받지 않는다.
    static String toAllowedProfileImageUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String url = rawUrl.trim();
        if (url.length() > PROFILE_IMAGE_URL_MAX_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean kakaoCdnHost = host.equals(KAKAO_CDN_DOMAIN) || host.endsWith("." + KAKAO_CDN_DOMAIN);
        if (!kakaoCdnHost || uri.getRawUserInfo() != null || uri.getPort() != -1
                || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        if (scheme.equals("http")) {
            url = "https" + url.substring(url.indexOf(':'));
        }
        return url;
    }

    // 로그인 응답용 - 허용 범위 밖이면 예외 대신 null(이미지 없이 가입 진행)
    private static String toAllowedProfileImageUrlOrNull(String rawUrl) {
        try {
            return toAllowedProfileImageUrl(rawUrl);
        } catch (CustomException e) {
            return null;
        }
    }
}
