package kr.silverbridge.main.domain.connection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Schema(description = "보호자 통화 우선순위 변경 요청")
@Getter
public class ConnectionPriorityUpdateRequest {

    @Schema(description = "변경할 우선순위 (1=1순위, 숫자가 낮을수록 먼저 연결 시도)", example = "1")
    @NotNull(message = "우선순위를 입력해주세요.")
    @Min(value = 1, message = "우선순위는 1 이상이어야 합니다.")
    private Integer priority;
}