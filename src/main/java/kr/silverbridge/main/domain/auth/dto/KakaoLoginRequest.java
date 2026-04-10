package kr.silverbridge.main.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@Schema(description = "카카오 로그인 요청")
public class KakaoLoginRequest {

    @Schema(description = "카카오 OAuth 인가 코드. 카카오 로그인 버튼 클릭 후 redirect_uri로 전달받은 code 파라미터 값", example = "abc123xyz")
    @NotBlank(message = "카카오 인가 코드는 필수입니다.")
    private String code;
}
