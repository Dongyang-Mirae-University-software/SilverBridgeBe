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

    @Schema(description = "연결 ID", example = "1")
    private Long id;

    @Schema(description = "보호자 UUID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    private String guardianId;

    @Schema(description = "보호자 이름", example = "김보호")
    private String guardianName;

    @Schema(description = "보호자 이메일", example = "guardian@example.com")
    private String guardianEmail;

    @Schema(description = "피보호자 UUID", example = "b2c3d4e5-f6a7-8901-bcde-f12345678901")
    private String wardId;

    @Schema(description = "피보호자 이름", example = "홍길동")
    private String wardName;

    @Schema(description = "피보호자 이메일", example = "ward@example.com")
    private String wardEmail;

    @Schema(description = "연결 상태. PENDING: 수락 대기, ACTIVE: 연결됨, CANCELLED: 해제됨", allowableValues = {"PENDING", "ACTIVE", "CANCELLED"}, example = "ACTIVE")
    private String status;

    @Schema(description = "연결 확정 일시 (ACTIVE 상태일 때만 존재, 그 외 null)", nullable = true)
    private OffsetDateTime connectedAt;

    @Schema(description = "연결 요청 생성 일시")
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
