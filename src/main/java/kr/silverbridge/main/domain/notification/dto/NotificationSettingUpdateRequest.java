package kr.silverbridge.main.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;

import java.util.List;

/**
 * 알림 채널 설정 변경 요청. 전달된 채널만 upsert하며, 생략된 채널은 기존 값(또는 기본값)을 유지한다.
 */
@Schema(description = "알림 채널 설정 변경 요청")
public record NotificationSettingUpdateRequest(
        @NotEmpty(message = "변경할 채널 설정을 하나 이상 포함해야 합니다.")
        @Valid
        @Schema(description = "변경할 채널 설정 목록")
        List<ChannelSettingUpdate> settings
) {
    @Schema(description = "단일 채널 설정 변경 항목")
    public record ChannelSettingUpdate(
            @NotNull(message = "channelType은 필수입니다.")
            @Schema(description = "채널 종류", example = "SMS")
            NotificationChannelType channelType,
            @NotNull(message = "enabled는 필수입니다.")
            @Schema(description = "활성화 여부", example = "true")
            Boolean enabled
    ) {}
}
