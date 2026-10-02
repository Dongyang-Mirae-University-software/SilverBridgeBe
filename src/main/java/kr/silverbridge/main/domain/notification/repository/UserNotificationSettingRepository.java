package kr.silverbridge.main.domain.notification.repository;

import kr.silverbridge.main.domain.notification.channel.NotificationChannelType;
import kr.silverbridge.main.domain.notification.entity.UserNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserNotificationSettingRepository extends JpaRepository<UserNotificationSetting, Long> {

    // 사용자의 모든 채널 설정 조회 (기본값 병합용)
    List<UserNotificationSetting> findByUserId(String userId);

    // 특정 채널 설정 조회 (upsert 시 기존 행 확인)
    Optional<UserNotificationSetting> findByUserIdAndChannelType(String userId, NotificationChannelType channelType);

    /**
     * (사용자, 채널) 행이 없을 때만 넣는다(USER-G12). 반환: 넣은 건수(0 = 이미 있었음 - 동시 요청이 먼저 넣었다).
     *
     * <p>"조회 후 없으면 save"는 같은 채널을 동시에 처음 켜면 둘 다 INSERT해 {@code uq_user_notif_channel} 위반(409)이
     * 났다. 위반 예외를 잡아 재조회하는 방식은 예외가 난 트랜잭션이 rollback-only가 돼 쓸 수 없어, DB가 충돌을
     * 흡수하게 했다. 시각 컬럼은 감사(@CreatedDate)를 거치지 않으므로 직접 채운다.</p>
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_notification_setting (user_id, channel_type, enabled, created_at, updated_at)
            VALUES (:userId, :channelType, :enabled, now(), now())
            ON CONFLICT (user_id, channel_type) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId,
                       @Param("channelType") String channelType,
                       @Param("enabled") boolean enabled);
}
