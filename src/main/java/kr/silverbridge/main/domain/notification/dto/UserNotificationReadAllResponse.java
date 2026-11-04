package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "모두 읽음 처리 결과")
public record UserNotificationReadAllResponse(
        @Schema(description = "이번에 읽음으로 바뀐 건수 (이미 읽은 것은 세지 않는다)", example = "3")
        int updated
) {
}
