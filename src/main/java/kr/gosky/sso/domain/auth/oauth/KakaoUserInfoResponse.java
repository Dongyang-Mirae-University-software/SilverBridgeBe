package kr.gosky.sso.domain.auth.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

// 카카오 사용자 정보 API 응답 DTO
@Getter
public class KakaoUserInfoResponse {

    // 카카오 사용자 고유 ID
    private Long id;

    @JsonProperty("kakao_account")
    private KakaoAccount kakaoAccount;

    @Getter
    public static class KakaoAccount {

        private String email;

        private Profile profile;

        @Getter
        public static class Profile {

            private String nickname;

            @JsonProperty("profile_image_url")
            private String profileImageUrl;
        }
    }

    public String getEmail() {
        return kakaoAccount != null ? kakaoAccount.getEmail() : null;
    }

    public String getNickname() {
        if (kakaoAccount != null && kakaoAccount.getProfile() != null) {
            return kakaoAccount.getProfile().getNickname();
        }
        return null;
    }

    public String getProfileImageUrl() {
        if (kakaoAccount != null && kakaoAccount.getProfile() != null) {
            return kakaoAccount.getProfile().getProfileImageUrl();
        }
        return null;
    }
}
