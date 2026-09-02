package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.config.FcmTokenProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 유휴 토큰 정리 스케줄러.
 *
 * <p>여기서 지키는 것은 둘이다 - <b>킬 스위치가 실제로 멈추는가</b>(잘못 지우는 게 확인됐을 때
 * 배포 없이 끌 수 있어야 한다)와 <b>실패가 스케줄러를 죽이지 않는가</b>(한 번 죽으면 이후 정리가
 * 영영 돌지 않는다).</p>
 */
@ExtendWith(MockitoExtension.class)
class FcmTokenCleanupSchedulerTest {

    @Mock private FcmService fcmService;
    @Spy private FcmTokenProperties properties = new FcmTokenProperties();

    @InjectMocks private FcmTokenCleanupScheduler scheduler;

    @Test
    @DisplayName("킬 스위치가 꺼져 있으면 정리를 시도조차 하지 않는다")
    void killSwitchStopsCleanup() {
        properties.setCleanupEnabled(false);

        scheduler.cleanupStaleTokens();

        verify(fcmService, never()).cleanupStaleTokens();
    }

    @Test
    @DisplayName("켜져 있으면 정리를 호출한다")
    void enabledRunsCleanup() {
        when(fcmService.cleanupStaleTokens()).thenReturn(2);

        scheduler.cleanupStaleTokens();

        verify(fcmService).cleanupStaleTokens();
    }

    @Test
    @DisplayName("정리가 실패해도 예외를 밖으로 내보내지 않는다 - 한 번 죽으면 이후 주기가 전부 사라진다")
    void failureDoesNotPropagate() {
        when(fcmService.cleanupStaleTokens()).thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(() -> scheduler.cleanupStaleTokens()).doesNotThrowAnyException();
    }
}
