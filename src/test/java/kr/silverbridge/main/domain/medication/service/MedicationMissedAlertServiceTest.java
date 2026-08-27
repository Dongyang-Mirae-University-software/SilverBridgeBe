package kr.silverbridge.main.domain.medication.service;

import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MedicationMissedAlertService 단위 테스트 — 문구와 실패 격리.
 *
 * <p>문구 검증이 핵심이다: 보호자에게 <b>"안 드셨다"고 단정하지 않는지</b>를 테스트로 고정한다.
 * 실제로는 복용하고 체크만 안 한 경우가 흔하다.</p>
 */
@ExtendWith(MockitoExtension.class)
class MedicationMissedAlertServiceTest {

    @Mock private MedicationMissedAlertPlanner planner;
    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private MedicationMissedAlertService missedAlertService;

    private static final String GUARDIAN_ID = "GD0001";
    private static final String WARD_ID = "WD0001";

    @Test
    @DisplayName("MEDICATION_MISSED로 '체크되지 않았다'고 알린다 — '안 드셨다'고 단정하지 않는다")
    void 문구_단정금지() {
        when(planner.claimMissedAlerts()).thenReturn(List.of(target(3, 1)));

        assertThat(missedAlertService.sendMissedAlerts()).isEqualTo(1);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(
                eq(GUARDIAN_ID), eq(NotificationType.MEDICATION_MISSED), captor.capture());

        NotificationContent content = captor.getValue();
        assertThat(content.title()).isEqualTo("복약 확인이 필요해요");
        assertThat(content.body())
                .contains("김영희", "3건", "1건", "체크되지 않았습니다")
                .doesNotContain("안 드셨", "복용하지 않");
        assertThat(content.data())
                .containsEntry("type", "MEDICATION_MISSED")
                .containsEntry("wardId", WARD_ID)
                .containsEntry("missedCount", "1")
                .containsEntry("totalCount", "3");
    }

    @Test
    @DisplayName("보낼 대상이 없으면 디스패처를 호출하지 않는다")
    void 대상없음() {
        when(planner.claimMissedAlerts()).thenReturn(List.of());

        assertThat(missedAlertService.sendMissedAlerts()).isZero();
        verify(notificationDispatcher, never()).dispatch(any(), any(), any());
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 계속 보내고 예외를 밖으로 내보내지 않는다")
    void 발송실패_격리() {
        when(planner.claimMissedAlerts()).thenReturn(List.of(target(2, 1), target(2, 2)));
        doThrow(new IllegalStateException("FCM 오류")).doNothing()
                .when(notificationDispatcher).dispatch(any(), any(), any());

        assertThatNoException().isThrownBy(() -> {
            assertThat(missedAlertService.sendMissedAlerts()).isEqualTo(1);
        });
        verify(notificationDispatcher, times(2)).dispatch(any(), any(), any());
    }

    @Test
    @DisplayName("보호자가 지정한 시각을 문구에 밝힌다 - 분모가 '오늘 전체'가 아님을 읽을 수 있어야 한다")
    void 문구_집계상한_노출() {
        when(planner.claimMissedAlerts()).thenReturn(List.of(target(2, 1, LocalTime.of(19, 30))));

        missedAlertService.sendMissedAlerts();

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(any(), any(), captor.capture());

        assertThat(captor.getValue().body()).contains("19:30까지 예정된");
        assertThat(captor.getValue().data()).containsEntry("alertTime", "19:30");
    }

    private static MedicationMissedAlertTarget target(int total, int missed) {
        return target(total, missed, LocalTime.of(21, 0));
    }

    private static MedicationMissedAlertTarget target(int total, int missed, LocalTime alertTime) {
        return new MedicationMissedAlertTarget(
                GUARDIAN_ID, WARD_ID, "김영희", MedicationClock.today(), alertTime, missed, total);
    }
}
