package kr.silverbridge.main.domain.sos.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.silverbridge.main.domain.sos.entity.SosAckStatus;

/**
 * SOS 처리 결과(ACK) 기록 요청.
 */
@Schema(description = "SOS 처리 결과 기록 요청")
public record SosAckRequest(

        @NotNull(message = "ackStatus는 필수입니다.")
        @Schema(description = "처리 결과", example = "SAFE_CONFIRMED",
                allowableValues = {"SAFE_CONFIRMED", "EMERGENCY_DISPATCHED"})
        SosAckStatus ackStatus,

        @Size(max = 200, message = "처리 메모는 200자를 초과할 수 없습니다.")
        @Schema(description = "처리 메모 (선택)", example = "통화 연결 · 안전 확인")
        String ackNote
) {}
