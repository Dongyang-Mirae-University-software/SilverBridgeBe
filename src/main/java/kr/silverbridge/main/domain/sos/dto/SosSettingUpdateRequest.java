package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.domain.sos.entity.SosAction;

/**
 * SOS 동작 설정 변경 요청.
 */
@Schema(description = "SOS 동작 설정 변경 요청")
public record SosSettingUpdateRequest(
        @NotNull(message = "sosAction은 필수입니다.")
        @Schema(description = "SOS 동작 방식", example = "NOTIFY_GUARDIAN_FIRST",
                allowableValues = {"CALL_119", "CALL_119_AND_NOTIFY", "NOTIFY_GUARDIAN_FIRST"})
        SosAction sosAction
) {}
