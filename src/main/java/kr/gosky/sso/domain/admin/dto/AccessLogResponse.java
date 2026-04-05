package kr.gosky.sso.domain.admin.dto;

import kr.gosky.sso.domain.auth.entity.AccessLog;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AccessLogResponse {

    private Long id;
    private String userId;
    private String clientId;
    private String action;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;

    public static AccessLogResponse from(AccessLog log) {
        return AccessLogResponse.builder()
                .id(log.getId())
                .userId(log.getUserId())
                .clientId(log.getClientId())
                .action(log.getAction())
                .ipAddress(log.getIpAddress())
                .userAgent(log.getUserAgent())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
