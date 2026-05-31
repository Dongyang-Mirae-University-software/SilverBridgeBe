package kr.silverbridge.main.domain.notification.entity;

import jakarta.persistence.*;
import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.global.entity.BaseTimeEntity;
import lombok.*;

/**
 * 사용자별 알림 채널 ON/OFF 설정. (user_id, channel_type) 한 쌍당 한 행.
 *
 * <p>행이 없는 채널은 "미설정"으로 보고 {@code NotificationSettingService}의 기본값 정책을 따른다
 * (기본 FCM ON, 그 외 OFF). 따라서 기존/신규 사용자에 대한 백필 마이그레이션이 필요 없다.</p>
 *
 * <p>users FK는 {@code ON DELETE CASCADE}(V25)라 회원 탈퇴(hard delete) 시 자동 정리된다.</p>
 */
@Entity
@Table(name = "user_notification_settings", uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_notif_channel", columnNames = {"user_id", "channel_type"})
}, indexes = {
        @Index(name = "idx_user_notif_user", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class UserNotificationSetting extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 6)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private NotificationChannelType channelType;

    @Column(nullable = false)
    private boolean enabled;

    public static UserNotificationSetting of(String userId, NotificationChannelType channelType, boolean enabled) {
        return UserNotificationSetting.builder()
                .userId(userId)
                .channelType(channelType)
                .enabled(enabled)
                .build();
    }

    public void updateEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
