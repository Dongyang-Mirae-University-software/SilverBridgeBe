package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.sos.entity.SosAckStatus;
import kr.silverbridge.main.domain.sos.entity.SosEvent;

import java.time.OffsetDateTime;

/**
 * 보호자 화면의 SOS 이력 한 건.
 *
 * <p>화면 문구("통화 연결 · 안전 확인" 등)는 프론트가 조립한다 — 백엔드가 문구를 만들면 UI 문구를 바꿀 때마다
 * 서버 배포가 필요해진다. 백엔드는 원자값(발생 시각·처리 결과·메모)만 준다.</p>
 *
 * @param sosEventId          SOS 이력 ID
 * @param wardId              SOS를 발생시킨 피보호자 ID (탈퇴 시 null)
 * @param wardName            피보호자 이름 (탈퇴·조회 실패 시 null)
 * @param triggeredAt         SOS 발생 시각
 * @param location            발생 위치 자유 문구. 프론트가 보내지 않았으면 null(위치 미상 → 화면에서 위치 줄 생략)
 * @param ackStatus           처리 결과. null이면 미처리
 * @param ackNote             처리 메모 (없으면 null)
 * @param acknowledgedByName  처리한 보호자 이름 (미처리·탈퇴 시 null)
 * @param acknowledgedAt      처리 시각 (미처리 시 null)
 */
@Schema(description = "보호자용 SOS 이력 항목")
public record SosHistoryItem(

        @Schema(description = "SOS 이력 ID", example = "42")
        Long sosEventId,

        @Schema(description = "피보호자 ID", example = "A1B2C3")
        String wardId,

        @Schema(description = "피보호자 이름", example = "김영희")
        String wardName,

        @Schema(description = "SOS 발생 시각")
        OffsetDateTime triggeredAt,

        @Schema(description = "발생 위치 (프론트가 보낸 값. 미상이면 null)", example = "자택 거실")
        String location,

        @Schema(description = "처리 결과 (null = 미처리)", example = "SAFE_CONFIRMED",
                allowableValues = {"SAFE_CONFIRMED", "EMERGENCY_DISPATCHED"})
        SosAckStatus ackStatus,

        @Schema(description = "처리 메모", example = "통화 연결 · 안전 확인")
        String ackNote,

        @Schema(description = "처리한 보호자 이름", example = "남궁명진")
        String acknowledgedByName,

        @Schema(description = "처리 시각")
        OffsetDateTime acknowledgedAt
) {
    public static SosHistoryItem of(SosEvent event, String wardName, String acknowledgedByName) {
        return new SosHistoryItem(
                event.getId(),
                event.getWardId(),
                wardName,
                event.getCreatedAt(),
                event.getLocation(),
                event.getAckStatus(),
                event.getAckNote(),
                event.isAcknowledged() ? acknowledgedByName : null,
                event.getAckAt()
        );
    }
}
