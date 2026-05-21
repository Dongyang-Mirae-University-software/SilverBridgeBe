package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.util.MaskingUtil;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 피보호자 "요청온 목록" 카드 응답.
 * 보호자가 보낸 PENDING 연결 요청을 수락/거절하기 위해 필요한 최소 정보만 노출한다.
 * 전화번호는 수락 전이므로 마스킹하고, 주소는 노출하지 않는다(수락 후 ACTIVE 응답에서만 노출).
 */
@Schema(description = "피보호자에게 온 PENDING 연결 요청")
@Getter
public class PendingConnectionResponse {

    @Schema(description = "연결 ID", example = "12")
    private final Long connectionId;

    @Schema(description = "보호자 사용자 ID")
    private final String guardianId;

    @Schema(description = "보호자 이름")
    private final String guardianName;

    @Schema(description = "보호자 전화번호 — PENDING이므로 마스킹 노출", example = "010****5678")
    private final String guardianPhone;

    @Schema(description = "보호자가 입력한 관계 (예: 아들). 기존 데이터(NULL)는 null", nullable = true, example = "아들")
    private final String relation;

    @Schema(description = "요청일(연결 생성 시각)", example = "2025-01-01T09:00:00+09:00")
    private final OffsetDateTime requestedAt;

    private PendingConnectionResponse(Long connectionId, String guardianId, String guardianName,
                                      String guardianPhone, String relation, OffsetDateTime requestedAt) {
        this.connectionId = connectionId;
        this.guardianId = guardianId;
        this.guardianName = guardianName;
        this.guardianPhone = guardianPhone;
        this.relation = relation;
        this.requestedAt = requestedAt;
    }

    public static PendingConnectionResponse from(Connection connection, User guardian) {
        return new PendingConnectionResponse(
                connection.getId(),
                guardian.getId(),
                guardian.getName(),
                MaskingUtil.maskPhone(guardian.getPhone()),
                connection.getRelation(),
                connection.getCreatedAt()
        );
    }
}
