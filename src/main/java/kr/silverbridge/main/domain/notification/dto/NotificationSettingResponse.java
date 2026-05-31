package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;

import java.util.List;

/**
 * 알림 설정 조회/변경 응답. 모든 채널의 현재 ON/OFF 상태를 기본값 병합 후 반환한다.
 */
@Schema(description = "사용자 알림 채널 설정")
public record NotificationSettingResponse(
        @Schema(description = "채널별 설정 목록(구현되지 않은 채널 포함 — 전체 채널 노출)")
        List<ChannelSetting> settings
) {
    @Schema(description = "단일 채널 설정")
    public record ChannelSetting(
            @Schema(description = "채널 종류", example = "FCM")
            NotificationChannelType channelType,
            @Schema(description = "활성화 여부", example = "true")
            boolean enabled
    ) {}

    public static NotificationSettingResponse of(List<ChannelSetting> settings) {
        return new NotificationSettingResponse(settings);
    }
}
