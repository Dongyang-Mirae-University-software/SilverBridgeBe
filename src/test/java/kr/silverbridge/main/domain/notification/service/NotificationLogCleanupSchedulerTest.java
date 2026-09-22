package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.config.NotificationLogProperties;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationLogCleanupSchedulerTest {

    @Mock private NotificationLogRepository notificationLogRepository;
    @Spy private NotificationLogProperties properties = new NotificationLogProperties();

    @InjectMocks private NotificationLogCleanupScheduler scheduler;

    @Test
    @DisplayName("보관 일수 이전을 기준으로 삭제한다(기본 90일)")
    void 보관기간_기준() {
        when(notificationLogRepository.deleteOlderThan(any())).thenReturn(3);

        scheduler.cleanup();

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(notificationLogRepository).deleteOlderThan(captor.capture());
        // 실행 시각에 흔들리지 않게 하루 폭으로 본다
        assertThat(captor.getValue())
                .isBefore(OffsetDateTime.now().minusDays(89))
                .isAfter(OffsetDateTime.now().minusDays(91));
    }

    @Test
    @DisplayName("킬 스위치가 꺼져 있으면 아무것도 지우지 않는다")
    void 킬스위치() {
        properties.setCleanupEnabled(false);

        scheduler.cleanup();

        verifyNoInteractions(notificationLogRepository);
    }

    @Test
    @DisplayName("삭제가 실패해도 예외를 밖으로 내지 않는다 - 다음 날 다시 시도한다")
    void 실패_삼킴() {
        when(notificationLogRepository.deleteOlderThan(any())).thenThrow(new RuntimeException("DB 장애"));

        assertThatNoException().isThrownBy(scheduler::cleanup);
    }
}
