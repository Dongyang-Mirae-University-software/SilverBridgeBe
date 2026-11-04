package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "안 읽은 알림 수")
public record UserNotificationUnreadCountResponse(
        @Schema(description = "최근 90일 동안 실제로 보낸 알림 중 안 읽은 수", example = "3")
        long count
) {
}
