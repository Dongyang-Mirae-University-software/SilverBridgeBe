package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.notification.service.FcmService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FcmNotificationChannelTest {

    @Mock private FcmService fcmService;
    @InjectMocks private FcmNotificationChannel channel;

    @Test
    @DisplayName("getType은 FCM")
    void getType_FCM() {
        assertThat(channel.getType()).isEqualTo(NotificationChannelType.FCM);
    }

    @Test
    @DisplayName("send는 FcmService.sendToUser에 그대로 위임한다")
    void send_FcmService위임() {
        NotificationRecipient recipient = new NotificationRecipient("WD0001", "01012345678", "a@b.com");
        Map<String, String> data = Map.of("type", "CONNECTION_REQUEST", "connectionId", "100");
        NotificationContent content = NotificationContent.of("연결 요청", "요청이 도착했습니다.", data);

        channel.send(recipient, content);

        verify(fcmService).sendToUser("WD0001", "연결 요청", "요청이 도착했습니다.", data);
    }
}
