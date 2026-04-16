package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = """
        카카오 로그인 응답.
        isNewUser 값에 따라 응답 구조가 달라집니다.
        - isNewUser=false (기존 회원): accessToken, refreshToken, userId, email, name, role 사용
        - isNewUser=true  (신규 회원): kakaoId, email, name, profileImageUrl 사용 → SMS 인증 후 POST /api/auth/kakao/register 호출
        """)
public class KakaoLoginResponse {

    @Schema(description = "신규 회원 여부. true면 회원가입 절차 필요, false면 바로 로그인 처리", example = "false")
    private boolean isNewUser;

    // 신규 회원 전용 필드
    @Schema(description = "[신규 회원 전용] 카카오 고유 ID. POST /api/auth/kakao/register 요청 시 그대로 전달", example = "3456789012")
    private String kakaoId;

    @Schema(description = "[신규 회원 전용] 카카오 계정 이메일. 회원가입 폼에 자동 입력용", example = "kakao_user@kakao.com")
    private String email;

    @Schema(description = "[신규 회원 전용] 카카오 닉네임. 회원가입 폼에 자동 입력용", example = "홍길동")
    private String name;

    @Schema(description = "[신규 회원 전용] 카카오 프로필 이미지 URL", example = "https://k.kakaocdn.net/dn/...")
    private String profileImageUrl;

    // 기존 회원 전용 필드
    @Schema(description = "[기존 회원 전용] API 호출 시 Authorization 헤더에 담을 토큰. 'Bearer {accessToken}' 형식으로 사용. 유효 시간: 30분", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String accessToken;

    @Schema(description = "[기존 회원 전용] Access Token 만료 시 재발급에 사용하는 토큰. POST /api/auth/refresh 에 전달. 유효 시간: 14일", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String refreshToken;

    @Schema(description = "[기존 회원 전용] 사용자 고유 ID (6자 영숫자)", example = "aB3x9Z")
    private String userId;

    @Schema(description = "[기존 회원 전용] 사용자 역할. WARD: 피보호자, GUARDIAN: 보호자", example = "GUARDIAN", allowableValues = {"WARD", "GUARDIAN"})
    private String role;

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
