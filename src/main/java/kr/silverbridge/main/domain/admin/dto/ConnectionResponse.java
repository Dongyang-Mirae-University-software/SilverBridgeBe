package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
@Schema(description = "연결 관계 응답")
public class ConnectionResponse {

    @Schema(description = "연결 ID")
    private Long id;

    @Schema(description = "보호자 ID")
    private String guardianId;

    @Schema(description = "보호자 이름")
    private String guardianName;

    @Schema(description = "보호자 이메일")
    private String guardianEmail;

    @Schema(description = "피보호자 ID")
    private String wardId;

    @Schema(description = "피보호자 이름")
    private String wardName;

    @Schema(description = "피보호자 이메일")
    private String wardEmail;

    @Schema(description = "연결 상태. PENDING: 수락 대기, ACTIVE: 연결됨, CANCELLED: 해제됨")
    private String status;

    @Schema(description = "연결 확정 시각 (ACTIVE 상태일 때만 존재)")
    private OffsetDateTime connectedAt;

    @Schema(description = "연결 요청 생성 시각")
    private OffsetDateTime createdAt;

    public static ConnectionResponse of(Connection connection, User guardian, User ward) {
        return ConnectionResponse.builder()
                .id(connection.getId())
                .guardianId(guardian.getId())
                .guardianName(guardian.getName())
                .guardianEmail(guardian.getEmail())
                .wardId(ward.getId())
                .wardName(ward.getName())
                .wardEmail(ward.getEmail())
                .status(connection.getStatus().name())
                .connectedAt(connection.getConnectedAt())
                .createdAt(connection.getCreatedAt())
                .build();
    }
}
