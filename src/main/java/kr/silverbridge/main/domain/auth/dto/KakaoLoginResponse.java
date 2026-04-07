package kr.silverbridge.main.domain.auth.dto;

import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KakaoLoginResponse {

    // 신규 사용자 여부 (true이면 역할 선택 필요)
    private boolean isNewUser;
    private String accessToken;
    private String refreshToken;  // 신규 사용자(PENDING)는 null
    private String userId;
    private String email;
    private String name;
    private String role;           // 신규 사용자(PENDING)는 null — 역할 선택 후 확정

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

    // 신규 사용자 응답 (역할 선택 대기, refreshToken 없음)
    public static KakaoLoginResponse ofNewUser(User user, String accessToken) {
        return KakaoLoginResponse.builder()
                .isNewUser(true)
                .accessToken(accessToken)
                .refreshToken(null)
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .build();
    }
}
