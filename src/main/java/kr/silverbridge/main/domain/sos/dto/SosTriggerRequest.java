package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.sos.entity.SosTriggerType;
import kr.silverbridge.main.global.validation.TextSanitizer;

/**
 * 긴급 SOS 발생 요청. <b>바디 전체가 선택</b>이다 - 바디 없이 호출하면 위치 미상 · 긴급 SOS 버튼 경로로
 * 기록된다(프론트가 위치·발생 경로를 보내기 전에도 기존과 동일하게 동작한다).
 *
 * @param location    발생 위치 자유 문구(선택, 100자). 서버는 위치를 추정하지 않고 받은 값을 그대로 보관한다
 * @param triggerType 발생 경로(선택). 생략하면 {@link SosTriggerType#SOS_BUTTON}. 보호자 카드를 눌러 전화를
 *                    걸면서 호출할 때만 {@code GUARDIAN_CALL}을 보낸다 - 어느 값이든 보호자 알림은 동일하게 나간다
 * @param calledGuardianId 전화를 건 보호자 ID(선택, 2026-10-30). {@code GUARDIAN_CALL}일 때만 의미가 있다. 형식 검사를
 *                    여기서 하지 않는다 - 잘못된 값으로 400이 나면 SOS 자체가 막히므로, 서비스가 걸러 저장하지 않을 뿐이다
 */
@Schema(description = "긴급 SOS 발생 요청 (바디 없이 호출 가능)")
public record SosTriggerRequest(

        @Size(max = 100, message = "위치는 100자를 초과할 수 없습니다.")
        @Schema(description = "발생 위치 (선택). 예 \"자택 거실\", \"역삼동 인근\"", example = "자택 거실")
        String location,

        @Schema(description = "발생 경로 (선택, 생략 시 SOS_BUTTON)", example = "SOS_BUTTON",
                allowableValues = {"SOS_BUTTON", "GUARDIAN_CALL"})
        SosTriggerType triggerType,

        @Schema(description = "전화를 건 보호자 ID (선택, GUARDIAN_CALL일 때만). 피보호자 보호자 목록의 partnerUserId. "
                + "연결된 보호자가 아니거나 형식이 틀려도 SOS는 그대로 접수되고 이 값만 저장되지 않습니다", example = "G1B2C3")
        String calledGuardianId
) {
    /** 위치는 앞뒤 공백·보이지 않는 문자를 정리한 뒤 길이를 검사한다. 정리 결과가 비면 null(위치는 선택). */
    public SosTriggerRequest {
        location = TextSanitizer.sanitize(location);
        if (location != null && location.isEmpty()) {
            location = null;
        }
    }
}
