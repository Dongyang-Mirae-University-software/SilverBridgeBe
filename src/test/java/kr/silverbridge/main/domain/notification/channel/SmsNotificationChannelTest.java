package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;

import kr.silverbridge.main.domain.auth.service.SmsSender;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsNotificationChannelTest {

    @Mock private SmsSender smsSender;
    @InjectMocks private SmsNotificationChannel channel;

    @Test
    @DisplayName("getType은 SMS")
    void getType_SMS() {
        assertThat(channel.getType()).isEqualTo(NotificationChannelType.SMS);
    }

    @Test
    @DisplayName("전화번호가 있으면 '[제목] 본문' 형태로 SmsSender에 위임하고 접수되면 전달로 반환한다")
    void send_전화번호있음_위임() {
        NotificationRecipient recipient = new NotificationRecipient("WD0001", "01012345678", null, Status.ACTIVE);
        NotificationContent content = NotificationContent.of("연결 요청", "요청이 도착했습니다.", Map.of());
        when(smsSender.trySend("01012345678", "[연결 요청] 요청이 도착했습니다.")).thenReturn(SmsSender.Outcome.SENT);

        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient, content);

        assertThat(result.isDelivered()).isTrue();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"REJECTED, PROVIDER_REJECTED", "ERROR, PROVIDER_ERROR"})
    @DisplayName("발송사 실패는 예외 대신 사유로 돌려준다 - 접수 거부와 통신 오류를 구분한다")
    void send_발송사실패_사유(SmsSender.Outcome outcome, ChannelFailureReason expected) {
        NotificationRecipient recipient = new NotificationRecipient("WD0001", "01012345678", null, Status.ACTIVE);
        NotificationContent content = NotificationContent.of("긴급 SOS", "도움 요청", Map.of());
        when(smsSender.trySend(anyString(), anyString())).thenReturn(outcome);

        ChannelResult result = channel.send(NotificationType.WARD_SOS, recipient, content);

        assertThat(result).isEqualTo(ChannelResult.failed(expected));
    }

    @Test
    @DisplayName("전화번호가 없으면 발송하지 않고 NO_PHONE 실패로 반환한다")
    void send_전화번호없음_미발송() {
        NotificationRecipient recipient = new NotificationRecipient("WD0001", null, null, Status.ACTIVE);
        NotificationContent content = NotificationContent.of("연결 요청", "요청이 도착했습니다.", Map.of());

        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient, content);

        assertThat(result).isEqualTo(ChannelResult.failed(ChannelFailureReason.NO_PHONE));
        verifyNoInteractions(smsSender);
    }
}
