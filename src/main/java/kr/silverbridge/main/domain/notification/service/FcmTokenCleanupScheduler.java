package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.config.FcmTokenProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 유휴 FCM 토큰 정리 스케줄러. 하루 한 번 돈다.
 *
 * <p>주기가 하루인 이유는 판정 기준이 "며칠 동안 갱신 없음"이라 분·시간 단위 정확도가 의미가 없기
 * 때문이다. 새벽 4시에 도는 것은 발송이 몰리는 시간(복약·재촉·이상감지)을 피하기 위해서다.</p>
 *
 * <p><b>예외를 삼킨다</b> - 한 주기의 실패가 스케줄러 자체를 멈추면 이후 정리가 전부 사라진다.
 * 로그만 남기고 다음 날 다시 시도한다(복약·재촉 스케줄러와 동일한 방침).</p>
 *
 * <p>{@code notification.fcm-token.cleanup-enabled=false}면 아무것도 하지 않는다 - 잘못 지우는 것이
 * 확인됐을 때 <b>배포 없이 즉시 멈추기</b> 위한 킬 스위치다. 토큰 등록·발송은 영향을 받지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcmTokenCleanupScheduler {

    private final FcmService fcmService;
    private final FcmTokenProperties properties;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void cleanupStaleTokens() {
        if (!properties.isCleanupEnabled()) {
            return;
        }

        try {
            fcmService.cleanupStaleTokens();
        } catch (RuntimeException e) {
            log.error("[FCM-TOKEN-CLEANUP] 유휴 토큰 정리 실패, 다음 주기에 재시도", e);
        }
    }
}
