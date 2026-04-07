package kr.gosky.sso.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class ClientRegisterRequest {

    @NotBlank(message = "클라이언트 ID는 필수입니다.")
    @Size(max = 100, message = "클라이언트 ID는 100자 이하여야 합니다.")
    private String clientId;

    @NotBlank(message = "서비스 이름은 필수입니다.")
    @Size(max = 100, message = "서비스 이름은 100자 이하여야 합니다.")
    private String clientName;

    @NotBlank(message = "redirect_uri는 필수입니다.")
    private String redirectUri;

    // 비우면 UUID 자동 생성
    private String clientSecret;
}
