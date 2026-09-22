package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.config.NotificationLogProperties;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 보관 기간이 지난 알림 이력 정리. 하루 한 번 돈다.
 *
 * <p>04:30인 이유 - 발송이 몰리는 시간(복약·재촉·이상감지)을 피하고, 04:00의 FCM 토큰 정리와 겹치지 않게 한다
 * (스케줄러 풀이 3스레드라 같은 시각에 몰리면 AI 재접속 예약이 밀린다).</p>
 *
 * <p><b>예외를 삼킨다</b> - 한 주기의 실패가 스케줄러를 멈추면 이후 정리가 전부 사라진다(다른 정리 스케줄러와 같은 방침).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationLogCleanupScheduler {

    private final NotificationLogRepository notificationLogRepository;
    private final NotificationLogProperties properties;

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        if (!properties.isCleanupEnabled()) {
            return;
        }

        try {
            OffsetDateTime threshold = OffsetDateTime.now().minusDays(properties.getRetentionDays());
            int deleted = notificationLogRepository.deleteOlderThan(threshold);
            if (deleted > 0) {
                log.info("[NOTIFY-LOG-CLEANUP] 보관 기간 지난 알림 이력 정리: {}건 (기준 {}일)",
                        deleted, properties.getRetentionDays());
            }
        } catch (RuntimeException e) {
            log.error("[NOTIFY-LOG-CLEANUP] 알림 이력 정리 실패, 다음 주기에 재시도", e);
        }
    }
}
