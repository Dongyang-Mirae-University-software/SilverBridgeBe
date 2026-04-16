package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Getter;

import java.time.OffsetDateTime;

@Schema(description = "연결 관계 응답")
@Getter
public class ConnectionResponse {

    @Schema(description = "연결 ID")
    private final Long id;

    @Schema(description = "상대방 사용자 ID")
    private final String partnerUserId;

    @Schema(description = "상대방 이름")
    private final String partnerName;

    @Schema(description = "상대방 프로필 이미지 URL", nullable = true)
    private final String partnerProfileImage;

    @Schema(description = "연결 상태", allowableValues = {"PENDING", "ACTIVE", "CANCELLED"})
    private final String status;

    @Schema(description = "통화 우선순위 (1=1순위)")
    private final int priority;

    @Schema(description = "요청자 여부 (true = 내가 요청한 연결)")
    private final boolean isRequester;

    @Schema(description = "연결 활성화 시각", example = "2025-01-01T09:00:00+09:00", nullable = true)
    private final OffsetDateTime connectedAt;

    @Schema(description = "연결 생성 시각", example = "2025-01-01T09:00:00+09:00")
    private final OffsetDateTime createdAt;

    private ConnectionResponse(Long id, String partnerUserId, String partnerName,
                               String partnerProfileImage, String status, int priority,
                               boolean isRequester, OffsetDateTime connectedAt, OffsetDateTime createdAt) {
        this.id = id;
        this.partnerUserId = partnerUserId;
        this.partnerName = partnerName;
        this.partnerProfileImage = partnerProfileImage;
        this.status = status;
        this.priority = priority;
        this.isRequester = isRequester;
        this.connectedAt = connectedAt;
        this.createdAt = createdAt;
    }

    // 보호자 관점: 피보호자 정보 반환
    public static ConnectionResponse fromGuardianView(Connection connection, User ward) {
        return new ConnectionResponse(
                connection.getId(),
                ward.getId(),
                ward.getName(),
                ward.getProfileImage(),
                connection.getStatus().name(),
                connection.getPriority(),
                connection.getGuardianId().equals(connection.getInitiatedBy()),
                connection.getConnectedAt(),
                connection.getCreatedAt()
        );
    }

    // 피보호자 관점: 보호자 정보 반환
    public static ConnectionResponse fromWardView(Connection connection, User guardian) {
        return new ConnectionResponse(
                connection.getId(),
                guardian.getId(),
                guardian.getName(),
                guardian.getProfileImage(),
                connection.getStatus().name(),
                connection.getPriority(),
                connection.getWardId().equals(connection.getInitiatedBy()),
                connection.getConnectedAt(),
                connection.getCreatedAt()
        );
    }
}