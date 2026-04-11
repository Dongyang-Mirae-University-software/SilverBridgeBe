package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.auth.entity.AccessLog;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
@Schema(description = "접속 로그 항목")
public class AccessLogResponse {

    @Schema(description = "로그 ID", example = "1")
    private Long id;

    @Schema(description = "사용자 UUID (탈퇴 시 null)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890", nullable = true)
    private String userId;

    @Schema(description = "액션 종류", allowableValues = {"LOGIN", "LOGOUT", "KAKAO_LOGIN", "TOKEN_ISSUE", "PASSWORD_RESET"}, example = "LOGIN")
    private String action;

    @Schema(description = "접속 IP", example = "192.168.0.1")
    private String ipAddress;

    @Schema(description = "User-Agent (브라우저/앱 정보)", example = "Mozilla/5.0 ...")
    private String userAgent;

    @Schema(description = "발생 일시", example = "2025-01-01T09:00:00+09:00")
    private OffsetDateTime createdAt;

    public static AccessLogResponse from(AccessLog log) {
        return AccessLogResponse.builder()
                .id(log.getId())
                .userId(log.getUserId())
                .action(log.getAction().name())
                .ipAddress(log.getIpAddress())
                .userAgent(log.getUserAgent())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
