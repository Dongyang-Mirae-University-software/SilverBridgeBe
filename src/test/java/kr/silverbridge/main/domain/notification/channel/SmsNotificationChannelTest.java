package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.auth.service.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @DisplayName("전화번호가 있으면 '[제목] 본문' 형태로 SmsSender에 위임한다")
    void send_전화번호있음_위임() {
        NotificationRecipient recipient = new NotificationRecipient("WD0001", "01012345678", null);
        NotificationContent content = NotificationContent.of("연결 요청", "요청이 도착했습니다.", Map.of());

        channel.send(recipient, content);

        verify(smsSender).send("01012345678", "[연결 요청] 요청이 도착했습니다.");
    }

    @Test
    @DisplayName("전화번호가 없으면 발송하지 않는다(건너뜀)")
    void send_전화번호없음_미발송() {
        NotificationRecipient recipient = new NotificationRecipient("WD0001", null, null);
        NotificationContent content = NotificationContent.of("연결 요청", "요청이 도착했습니다.", Map.of());

        channel.send(recipient, content);

        verifyNoInteractions(smsSender);
    }
}
