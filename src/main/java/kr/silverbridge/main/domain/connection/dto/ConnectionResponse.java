package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.connection.entity.Connection;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.ConnectionStatus;
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

    @Schema(description = "상대방 전화번호 — ACTIVE 상태에서만 노출, PENDING/CANCELLED은 null", nullable = true, example = "010-1234-5678")
    private final String partnerPhone;

    @Schema(description = "상대방 주소 — ACTIVE 상태에서만 노출, PENDING/CANCELLED은 null", nullable = true, example = "서울시 강남구 역삼로 123")
    private final String partnerAddress;

    @Schema(description = "상대방 상세주소 — ACTIVE 상태에서만 노출, PENDING/CANCELLED은 null", nullable = true)
    private final String partnerAddressDetail;

    @Schema(description = "보호자가 입력한 피보호자와의 관계 (예: 아들). 기존 데이터(NULL)는 응답에서도 null", nullable = true, example = "아들")
    private final String relation;

    @Schema(description = "연결 상태", allowableValues = {"PENDING", "ACTIVE", "CANCELLED"})
    private final String status;

    @Schema(description = "요청자 여부 (true = 내가 요청한 연결)")
    private final boolean isRequester;

    @Schema(description = "연결 활성화 시각", example = "2025-01-01T09:00:00+09:00", nullable = true)
    private final OffsetDateTime connectedAt;

    @Schema(description = "연결 생성 시각(요청일)", example = "2025-01-01T09:00:00+09:00")
    private final OffsetDateTime createdAt;

    private ConnectionResponse(Long id, String partnerUserId, String partnerName,
                               String partnerProfileImage, String partnerPhone,
                               String partnerAddress, String partnerAddressDetail,
                               String relation, String status,
                               boolean isRequester, OffsetDateTime connectedAt,
                               OffsetDateTime createdAt) {
        this.id = id;
        this.partnerUserId = partnerUserId;
        this.partnerName = partnerName;
        this.partnerProfileImage = partnerProfileImage;
        this.partnerPhone = partnerPhone;
        this.partnerAddress = partnerAddress;
        this.partnerAddressDetail = partnerAddressDetail;
        this.relation = relation;
        this.status = status;
        this.isRequester = isRequester;
        this.connectedAt = connectedAt;
        this.createdAt = createdAt;
    }

    // 보호자 관점: 피보호자 정보 반환 (PENDING/CANCELLED에서는 phone/address null)
    public static ConnectionResponse fromGuardianView(Connection connection, User ward) {
        boolean revealContact = connection.getStatus() == ConnectionStatus.ACTIVE;
        return new ConnectionResponse(
                connection.getId(),
                ward.getId(),
                ward.getName(),
                ward.getProfileImage(),
                revealContact ? ward.getPhone() : null,
                revealContact ? ward.getAddress() : null,
                revealContact ? ward.getAddressDetail() : null,
                connection.getRelation(),
                connection.getStatus().name(),
                connection.getGuardianId().equals(connection.getInitiatedBy()),
                connection.getConnectedAt(),
                connection.getCreatedAt()
        );
    }

    // 피보호자 관점: 보호자 정보 반환 (PENDING/CANCELLED에서는 phone/address null)
    public static ConnectionResponse fromWardView(Connection connection, User guardian) {
        boolean revealContact = connection.getStatus() == ConnectionStatus.ACTIVE;
        return new ConnectionResponse(
                connection.getId(),
                guardian.getId(),
                guardian.getName(),
                guardian.getProfileImage(),
                revealContact ? guardian.getPhone() : null,
                revealContact ? guardian.getAddress() : null,
                revealContact ? guardian.getAddressDetail() : null,
                connection.getRelation(),
                connection.getStatus().name(),
                connection.getWardId().equals(connection.getInitiatedBy()),
                connection.getConnectedAt(),
                connection.getCreatedAt()
        );
    }
}
