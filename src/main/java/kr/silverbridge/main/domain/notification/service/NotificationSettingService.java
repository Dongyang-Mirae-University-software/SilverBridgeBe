package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingResponse;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingResponse.ChannelSetting;
import kr.silverbridge.main.domain.notification.dto.NotificationSettingUpdateRequest;
import kr.silverbridge.main.domain.notification.entity.UserNotificationSetting;
import kr.silverbridge.main.domain.notification.repository.UserNotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 알림 채널 설정 조회/변경 및 "활성 채널" 계산을 담당한다.
 *
 * <p><b>기본값 정책</b>: 설정 행이 없는 채널은 {@link #DEFAULT_ENABLED_CHANNELS}를 따른다.
 * 기본은 FCM만 ON — 기존 동작(연결 알림 = FCM 발송)을 그대로 보존하고, 신규/기존 사용자에 대한
 * 백필 마이그레이션을 불필요하게 만든다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    /** 설정 행이 없을 때 적용되는 기본 활성 채널. FCM만 ON(기존 동작 보존). */
    private static final Set<NotificationChannelType> DEFAULT_ENABLED_CHANNELS =
            EnumSet.of(NotificationChannelType.FCM);

    private final UserNotificationSettingRepository repository;

    /**
     * 사용자의 활성 채널 집합. 디스패처가 "선택" 알림을 라우팅할 때 사용한다.
     * 저장된 행이 우선하고, 행이 없는 채널은 기본값 정책을 적용한다.
     */
    @Transactional(readOnly = true)
    public Set<NotificationChannelType> enabledChannels(String userId) {
        Map<NotificationChannelType, Boolean> stored = storedSettings(userId);
        Set<NotificationChannelType> result = EnumSet.noneOf(NotificationChannelType.class);
        for (NotificationChannelType type : NotificationChannelType.values()) {
            if (isEnabled(type, stored)) {
                result.add(type);
            }
        }
        return result;
    }

    /** 전체 채널의 현재 설정(기본값 병합)을 조회한다. */
    @Transactional(readOnly = true)
    public NotificationSettingResponse getSettings(String userId) {
        Map<NotificationChannelType, Boolean> stored = storedSettings(userId);
        List<ChannelSetting> settings = Arrays.stream(NotificationChannelType.values())
                .map(type -> new ChannelSetting(type, isEnabled(type, stored)))
                .toList();
        return NotificationSettingResponse.of(settings);
    }

    /** 전달된 채널만 upsert하고, 갱신된 전체 설정을 반환한다. */
    @Transactional
    public NotificationSettingResponse updateSettings(String userId, NotificationSettingUpdateRequest request) {
        for (var item : request.settings()) {
            repository.findByUserIdAndChannelType(userId, item.channelType())
                    .ifPresentOrElse(
                            existing -> existing.updateEnabled(item.enabled()),
                            () -> repository.save(
                                    UserNotificationSetting.of(userId, item.channelType(), item.enabled())));
        }
        return getSettings(userId);
    }

    private Map<NotificationChannelType, Boolean> storedSettings(String userId) {
        Map<NotificationChannelType, Boolean> map = new EnumMap<>(NotificationChannelType.class);
        for (UserNotificationSetting setting : repository.findByUserId(userId)) {
            map.put(setting.getChannelType(), setting.isEnabled());
        }
        return map;
    }

    private boolean isEnabled(NotificationChannelType type, Map<NotificationChannelType, Boolean> stored) {
        Boolean storedValue = stored.get(type);
        return (storedValue != null) ? storedValue : DEFAULT_ENABLED_CHANNELS.contains(type);
    }
}