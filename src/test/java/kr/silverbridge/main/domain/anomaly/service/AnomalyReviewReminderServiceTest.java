package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.DetectedType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 동수 재확인 안내 발송 검증(2026-09-21).
 *
 * <p>고정하는 것은 셋이다 - 전용 알림 종류로 나간다(재촉과 섞이지 않게), 누가 무엇이라 답했는지는 싣지 않는다,
 * 한 건의 발송 실패가 나머지를 막지 않는다(선점했으므로 재시도하지 않는다).</p>
 */
@ExtendWith(MockitoExtension.class)
class AnomalyReviewReminderServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Mock private AnomalyReviewReminderPlanner planner;
    @Mock private NotificationDispatcher notificationDispatcher;

    private AnomalyReviewReminderService service;

    @BeforeEach
    void setUp() {
        service = new AnomalyReviewReminderService(planner, notificationDispatcher);
    }

    private AnomalyReviewReminderTarget target(String guardianId) {
        return new AnomalyReviewReminderTarget(guardianId, 37L, "WD0001", "김영희", "거실",
                DetectedType.FIRE, OffsetDateTime.of(2026, 9, 21, 14, 5, 0, 0, KST));
    }

    @Test
    @DisplayName("동수 안내는 ANOMALY_REVIEW_CONFLICTED로 나가고, 다른 보호자의 답은 문구에 싣지 않는다")
    void conflictNoticeContent() {
        when(planner.claimConflicts()).thenReturn(List.of(target("GD0002")));

        int sent = service.sendConflictNotices();

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq("GD0002"), eq(NotificationType.ANOMALY_REVIEW_CONFLICTED), captor.capture());
        NotificationContent content = captor.getValue();

        assertThat(sent).isEqualTo(1);
        assertThat(content.body())
                .contains("9월 21일 14:05", "김영희님", "거실", "화재 감지", "다른 보호자와 판정이 다릅니다")
                .doesNotContain("오탐", "실제 위험", "발생했습니다");
        assertThat(content.data())
                .containsEntry("type", "ANOMALY_REVIEW_CONFLICTED")
                .containsEntry("incidentId", "37")
                .containsEntry("wardId", "WD0001");
    }

    @Test
    @DisplayName("한 명에게 발송이 실패해도 나머지에게는 보낸다 - 선점했으므로 재시도하지 않는다")
    void failureDoesNotStopOthers() {
        when(planner.claimConflicts()).thenReturn(List.of(target("GD0002"), target("GD0003")));
        doThrow(new IllegalStateException("FCM down"))
                .when(notificationDispatcher).dispatch(eq("GD0002"), any(), any());

        int sent = service.sendConflictNotices();

        assertThat(sent).isEqualTo(1);
        verify(notificationDispatcher, times(2)).dispatch(any(), eq(NotificationType.ANOMALY_REVIEW_CONFLICTED), any());
    }
}
