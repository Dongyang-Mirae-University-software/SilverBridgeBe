package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.sos.entity.SosAction;

/**
 * SOS 동작 설정 조회/변경 응답. 저장된 값이 없으면 기본값을 병합해 반환한다.
 */
@Schema(description = "피보호자 SOS 동작 설정")
public record SosSettingResponse(
        @Schema(description = "SOS 동작 방식", example = "CALL_119_AND_NOTIFY")
        SosAction sosAction
) {
    public static SosSettingResponse of(SosAction sosAction) {
        return new SosSettingResponse(sosAction);
    }
}
