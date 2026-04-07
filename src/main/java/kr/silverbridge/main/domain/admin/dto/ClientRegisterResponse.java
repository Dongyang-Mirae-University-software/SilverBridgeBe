package kr.silverbridge.main.domain.admin.dto;

import lombok.Builder;
import lombok.Getter;

// 서비스 등록 응답 — client_secret은 이 응답에서 딱 한 번만 노출
@Getter
@Builder
public class ClientRegisterResponse {

    private String clientId;
    private String clientName;
    private String clientSecret;
    private String redirectUri;
}
