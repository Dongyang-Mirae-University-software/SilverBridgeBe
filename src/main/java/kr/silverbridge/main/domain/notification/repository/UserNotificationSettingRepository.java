package kr.silverbridge.main.domain.notification.repository;

import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.entity.UserNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserNotificationSettingRepository extends JpaRepository<UserNotificationSetting, Long> {

    // 사용자의 모든 채널 설정 조회 (기본값 병합용)
    List<UserNotificationSetting> findByUserId(String userId);

    // 특정 채널 설정 조회 (upsert 시 기존 행 확인)
    Optional<UserNotificationSetting> findByUserIdAndChannelType(String userId, NotificationChannelType channelType);
}
