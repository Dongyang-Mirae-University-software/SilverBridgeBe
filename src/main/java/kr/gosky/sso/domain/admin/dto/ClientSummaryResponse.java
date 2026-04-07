package kr.gosky.sso.domain.admin.dto;

import kr.gosky.sso.domain.admin.entity.SsoClient;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// 서비스 목록 조회 응답 — client_secret 미포함
@Getter
@Builder
public class ClientSummaryResponse {

    private Long id;
    private String clientId;
    private String clientName;
    private String redirectUri;
    private boolean isActive;
    private LocalDateTime createdAt;

    public static ClientSummaryResponse from(SsoClient client) {
        return ClientSummaryResponse.builder()
                .id(client.getId())
                .clientId(client.getClientId())
                .clientName(client.getClientName())
                .redirectUri(client.getRedirectUri())
                .isActive(client.isActive())
                .createdAt(client.getCreatedAt())
                .build();
    }
}
