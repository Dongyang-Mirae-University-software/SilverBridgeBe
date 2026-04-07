package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.dto.KakaoLoginRequest;
import kr.silverbridge.main.domain.auth.dto.LoginResponse;
import kr.silverbridge.main.domain.auth.entity.RefreshToken;
import kr.silverbridge.main.domain.auth.oauth.KakaoOAuthClient;
import kr.silverbridge.main.domain.auth.oauth.KakaoTokenResponse;
import kr.silverbridge.main.domain.auth.oauth.KakaoUserInfoResponse;
import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.enums.Provider;
import kr.silverbridge.main.global.enums.Role;
import kr.silverbridge.main.global.enums.Status;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import kr.silverbridge.main.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KakaoAuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthService authService;

    // 카카오 로그인
    // 인가 코드 → 카카오 토큰 → 카카오 사용자 정보 → 회원 조회/생성 → JWT 발급
    @Transactional
    public LoginResponse kakaoLogin(KakaoLoginRequest request, String ipAddress, String userAgent) {
        // 1. 카카오 인가 코드 → 카카오 액세스 토큰 교환
        KakaoTokenResponse kakaoToken = kakaoOAuthClient.getToken(request.getCode());

        // 2. 카카오 액세스 토큰으로 사용자 정보 조회
        KakaoUserInfoResponse kakaoUser = kakaoOAuthClient.getUserInfo(kakaoToken.getAccessToken());

        // 3. 카카오 ID로 기존 사용자 조회, 없으면 신규 생성
        String kakaoId = String.valueOf(kakaoUser.getId());
        User user = userRepository.findByProviderAndProviderId(Provider.KAKAO, kakaoId)
                .orElseGet(() -> createKakaoUser(kakaoUser, kakaoId));

        // 4. 탈퇴(비활성화) 계정 접근 차단
        if (user.getStatus() == Status.INACTIVE) {
            throw new CustomException(ErrorCode.INACTIVE_USER);
        }

        // 5. JWT 발급 및 Refresh Token 저장 (단일 디바이스 정책)
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        refreshTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .token(refreshToken)
                .expiresAt(LocalDateTime.now().plusSeconds(
                        jwtTokenProvider.getRemainingExpiration(refreshToken) / 1000))
                .build());

        user.updateLastLoginAt();
        authService.saveAccessLog(user.getId(), "KAKAO_LOGIN", ipAddress, userAgent);

        return LoginResponse.of(user, accessToken, refreshToken);
    }

    // 카카오 신규 사용자 생성
    private User createKakaoUser(KakaoUserInfoResponse kakaoUser, String kakaoId) {
        String email = kakaoUser.getEmail();
        // 이메일 제공 동의를 하지 않은 경우 카카오 ID 기반 임시 이메일 생성
        if (email == null || email.isBlank()) {
            email = "kakao_" + kakaoId + "@kakao.com";
        } else if (userRepository.existsByEmail(email)) {
            // 이미 같은 이메일로 다른 계정이 있는 경우 충돌 처리
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        String nickname = kakaoUser.getNickname();
        if (nickname == null || nickname.isBlank()) {
            nickname = "카카오사용자";
        }

        User newUser = User.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .password(null)             // 소셜 로그인 사용자는 비밀번호 없음
                .name(nickname)
                .phone(null)
                // TODO: 카카오 최초 가입 시 역할 선택 플로우 별도 구현 필요 (현재 WARD 임시 기본값)
                .role(Role.WARD)
                .status(Status.ACTIVE)
                .provider(Provider.KAKAO)
                .providerId(kakaoId)
                .profileImage(kakaoUser.getProfileImageUrl())
                .emailVerified(true)        // 카카오 계정은 이메일 인증된 것으로 간주
                .build();

        return userRepository.save(newUser);
    }
}
