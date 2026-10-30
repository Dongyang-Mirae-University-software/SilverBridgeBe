package kr.silverbridge.main.domain.sos.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param pressCount       연타 묶음의 누름 수. 묶음 조회({@code group=true})에서만 채우고 그 밖에는 null(JSON에서 생략)
 * @param firstTriggeredAt 묶음의 첫 누름 시각. 묶음 조회에서만
 * @param lastTriggeredAt  묶음의 마지막 누름 시각({@code triggeredAt}과 같다). 묶음 조회에서만
 * @param calledGuardianName 보호자 연락(GUARDIAN_CALL)에서 전화받은 보호자 이름. 이력 조회에서, 그 보호자가 <b>지금</b> 그
 *                           피보호자와 ACTIVE 연결일 때만 채운다(해제·탈퇴·미기록이면 null - JSON에서 생략). 사용자 ID는 내리지
 *                           않는다 - 칩에는 이름만 필요하고, 보호자끼리 서로의 ID를 볼 이유가 없다(2026-10-30 점검 M-1)
 *
 * <p>묶음 항목의 {@code sosEventId}·{@code triggeredAt}·{@code location}은 <b>묶음의 최신 건</b> 값이다
 * (2026-10-30). 세 칸은 기존 응답·대시보드 응답을 바꾸지 않도록 null이면 내리지 않는다.</p>
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
        SosTriggerType triggerType,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "연타 묶음의 누름 수 (group=true일 때만)", example = "2")
        Long pressCount,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "묶음의 첫 누름 시각 (group=true일 때만)")
        OffsetDateTime firstTriggeredAt,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "묶음의 마지막 누름 시각 = triggeredAt (group=true일 때만)")
        OffsetDateTime lastTriggeredAt,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "전화받은 보호자 이름 (보호자 연락이고 그 보호자가 지금 연결돼 있을 때만)", example = "박지영")
        String calledGuardianName
) {
    public static SosHistoryItem of(SosEvent event, String wardName) {
        return new SosHistoryItem(
                event.getId(),
                event.getWardId(),
                wardName,
                event.getCreatedAt(),
                event.getLocation(),
                event.getTriggerType(),
                null,
                null,
                null,
                null
        );
    }

    /** 이 항목에 전화받은 보호자 이름을 붙인 사본. {@code name}이 null이면 칸을 비운다. */
    public SosHistoryItem withCalledGuardianName(String name) {
        return new SosHistoryItem(sosEventId, wardId, wardName, triggeredAt, location, triggerType,
                pressCount, firstTriggeredAt, lastTriggeredAt, name);
    }

    /**
     * 연타 묶음 한 칸. 표시 값은 최신 건({@code latest})을 쓰고 첫 누름 시각만 {@code first}에서 가져온다.
     *
     * @param latest     묶음의 마지막 누름
     * @param first      묶음의 첫 누름 (1번 누름이면 {@code latest}와 같다)
     * @param pressCount 묶음의 누름 수
     */
    public static SosHistoryItem ofGroup(SosEvent latest, SosEvent first, long pressCount, String wardName) {
        return new SosHistoryItem(
                latest.getId(),
                latest.getWardId(),
                wardName,
                latest.getCreatedAt(),
                latest.getLocation(),
                latest.getTriggerType(),
                pressCount,
                first.getCreatedAt(),
                latest.getCreatedAt(),
                null
        );
    }
}
