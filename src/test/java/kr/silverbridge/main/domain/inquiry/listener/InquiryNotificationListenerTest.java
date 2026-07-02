package kr.silverbridge.main.domain.inquiry.listener;

import kr.silverbridge.main.domain.inquiry.event.InquiryAnsweredEvent;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * InquiryNotificationListener 단위 테스트.
 * AFTER_COMMIT 핸들러를 직접 호출해 작성자에게 선택 알림(INQUIRY_ANSWERED)이 올바른 문구·타입으로
 * 디스패치되는지 검증한다. 발송 채널 결정은 NotificationDispatcher가 담당(선택 알림 = 사용자 설정 따름).
 */
@ExtendWith(MockitoExtension.class)
class InquiryNotificationListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private InquiryNotificationListener listener;

    @Test
    @DisplayName("답변 완료 이벤트 → 작성자에게 INQUIRY_ANSWERED 선택 알림 디스패치")
    void handleAnswered_작성자에게_답변알림() {
        InquiryAnsweredEvent event = new InquiryAnsweredEvent(1L, "GD0001");

        listener.handleAnswered(event);

        ArgumentCaptor<NotificationContent> captor = ArgumentCaptor.forClass(NotificationContent.class);
        verify(notificationDispatcher).dispatch(eq("GD0001"), eq(NotificationType.INQUIRY_ANSWERED), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("문의 답변 완료");
        assertThat(captor.getValue().body()).isEqualTo("문의하신 내용에 답변이 등록되었습니다.");
        assertThat(captor.getValue().data()).containsEntry("type", "INQUIRY_ANSWERED");
        assertThat(captor.getValue().data()).containsEntry("inquiryId", "1");
    }
}
