package kr.silverbridge.main.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.anomaly.entity.AnomalyEvent;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.global.enums.AnomalyEventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "이상감지 이벤트 응답")
public record AnomalyEventResponse(

        @Schema(description = "이벤트 ID", example = "1")
        Long id,

        @Schema(description = "피보호자 UUID", example = "uuid-ward-1234")
        String wardId,

        @Schema(description = "피보호자 이름", example = "홍길동")
        String wardName,

        @Schema(description = "피보호자 이메일", example = "ward@example.com")
        String wardEmail,

        @Schema(description = "이벤트 유형", allowableValues = {"FIRE", "WEAPON", "FALL"}, example = "FALL")
        AnomalyEventType eventType,

        @Schema(description = "감지 신뢰도 (0.00 ~ 1.00)", example = "0.92")
        BigDecimal confidence,

        @Schema(description = "감지 일시")
        OffsetDateTime detectedAt,

        @Schema(description = "보호자 확인 여부", example = "false")
        boolean isConfirmed,

        @Schema(description = "수신 일시")
        OffsetDateTime createdAt
) {

    public static AnomalyEventResponse of(AnomalyEvent event, User ward) {
        return new AnomalyEventResponse(
                event.getId(),
                ward != null ? ward.getId() : event.getWardId(),
                ward != null ? ward.getName() : null,
                ward != null ? ward.getEmail() : null,
                event.getEventType(),
                event.getConfidence(),
                event.getDetectedAt(),
                event.isConfirmed(),
                event.getCreatedAt()
        );
    }

    // 피보호자 탈퇴 등으로 ward가 없는 경우
    public static AnomalyEventResponse ofDeleted(AnomalyEvent event) {
        return new AnomalyEventResponse(
                event.getId(),
                event.getWardId(),
                null,
                null,
                event.getEventType(),
                event.getConfidence(),
                event.getDetectedAt(),
                event.isConfirmed(),
                event.getCreatedAt()
        );
    }
}
