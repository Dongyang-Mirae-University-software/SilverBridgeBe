package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션 만료(자동 로그아웃) 뒤 이 기기의 FCM 토큰을 해제하는 요청 (XAREA-G01).
 *
 * <p>access token을 헤더가 아니라 본문으로 받는다 - 인증 필터는 만료된 Bearer 헤더를 401로 막기 때문이다.
 * 쿼리 문자열로 받지 말 것(프록시·접속 로그에 토큰이 남는다).</p>
 */
@Schema(description = "세션 만료 후 FCM 토큰 해제 요청")
@Getter
@NoArgsConstructor
public class FcmTokenReleaseRequest {

    @Schema(description = "마지막으로 쓰던 access token(만료돼도 됨). Authorization 헤더가 아니라 본문으로 보낸다")
    @NotBlank(message = "accessToken을 입력해주세요.")
    @Size(max = 4096, message = "accessToken 형식이 올바르지 않습니다.")
    private String accessToken;

    @Schema(description = "이 기기의 FCM 토큰", example = "dGhpcyBpcyBhIHNhbXBsZSB0b2tlbg...")
    @NotBlank(message = "FCM 토큰을 입력해주세요.")
    @Size(max = 500, message = "FCM 토큰 형식이 올바르지 않습니다.")
    private String token;

    public FcmTokenReleaseRequest(String accessToken, String token) {
        this.accessToken = accessToken;
        this.token = token;
    }
}
