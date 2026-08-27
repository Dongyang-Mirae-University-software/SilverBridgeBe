package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.sos.entity.SosEvent;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;

import java.time.OffsetDateTime;

/**
 * 보호자 화면의 SOS 이력 한 건.
 *
 * <p>화면 문구("자택 거실에서 긴급 요청" 등)는 프론트가 조립한다 - 백엔드가 문구를 만들면 UI 문구를 바꿀 때마다
 * 서버 배포가 필요해진다. 백엔드는 원자값(발생 시각·위치·경로)만 준다.</p>
 *
 * <p>이력은 <b>"언제·어떤 경로로 발생했는가"까지만</b> 답한다(2026-08-26, V39). 처리 결과(ACK) 필드는
 * 기능 철회와 함께 제거했다.</p>
 *
 * @param sosEventId  SOS 이력 ID
 * @param wardId      SOS를 발생시킨 피보호자 ID (탈퇴 시 null)
 * @param wardName    피보호자 이름 (탈퇴·조회 실패 시 null)
 * @param triggeredAt SOS 발생 시각
 * @param location    발생 위치 자유 문구. 프론트가 보내지 않았으면 null(위치 미상 → 화면에서 위치 줄 생략)
 * @param triggerType 발생 경로 - 긴급 SOS 버튼(SOS_BUTTON) / 보호자에게 직접 전화(GUARDIAN_CALL)
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

        @Schema(description = "발생 경로", example = "SOS_BUTTON",
                allowableValues = {"SOS_BUTTON", "GUARDIAN_CALL"})
        SosTriggerType triggerType
) {
    public static SosHistoryItem of(SosEvent event, String wardName) {
        return new SosHistoryItem(
                event.getId(),
                event.getWardId(),
                wardName,
                event.getCreatedAt(),
                event.getLocation(),
                event.getTriggerType()
        );
    }
}
