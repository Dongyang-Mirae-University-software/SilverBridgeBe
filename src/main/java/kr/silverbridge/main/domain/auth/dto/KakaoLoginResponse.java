package kr.silverbridge.main.domain.auth.dto;

import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KakaoLoginResponse {

    private boolean isNewUser;

    // 신규 사용자 전용 (가입 폼에 표시용)
    private String kakaoId;
    private String email;
    private String name;
    private String profileImageUrl;

    // 기존 사용자 전용
    private String accessToken;
    private String refreshToken;
    private String userId;
    private String role;

    // 기존 사용자 로그인 응답
    public static KakaoLoginResponse ofExisting(User user, String accessToken, String refreshToken) {
        return KakaoLoginResponse.builder()
                .isNewUser(false)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole().name())
                .build();
    }

    // 신규 사용자 응답 (DB 저장 전, 토큰 없음)
    public static KakaoLoginResponse ofNewUser(String kakaoId, String email, String name, String profileImageUrl) {
        return KakaoLoginResponse.builder()
                .isNewUser(true)
                .kakaoId(kakaoId)
                .email(email)
                .name(name)
                .profileImageUrl(profileImageUrl)
                .build();
    }
}
