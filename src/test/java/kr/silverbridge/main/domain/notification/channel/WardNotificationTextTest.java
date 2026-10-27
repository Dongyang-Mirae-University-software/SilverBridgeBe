package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.client.SmsSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WardNotificationTextTest {

    @Mock private SmsSender smsSender;
    @InjectMocks private SmsNotificationChannel channel;

    @Test
    @DisplayName("EUC-KR 바이트: 한글 2·영숫자 1, EUC-KR에 없는 글자(이모지)는 2로 센다")
    void eucKrBytes() {
        assertThat(WardNotificationText.eucKrBytes("약 드실 시간이에요.")).isEqualTo(19);
        assertThat(WardNotificationText.eucKrBytes("112")).isEqualTo(3);
        assertThat(WardNotificationText.eucKrBytes("😀")).isEqualTo(2);
    }

    @Test
    @DisplayName("90바이트 경계: 90은 단문, 91은 넘는다")
    void fitsSingleSms_경계() {
        int room = WardNotificationText.SINGLE_SMS_MAX_BYTES
                - WardNotificationText.eucKrBytes(WardNotificationText.smsLine(""));   // "[실버브릿지] " = 13바이트
        assertThat(WardNotificationText.fitsSingleSms("a".repeat(room))).isTrue();
        assertThat(WardNotificationText.fitsSingleSms("a".repeat(room + 1))).isFalse();
    }

    @Test
    @DisplayName("피보호자 제목으로 보내면 문자 채널이 만드는 한 줄이 smsLine과 같다 - 제목이 한 번만 앞에 붙는다")
    void 문자채널_한줄과_같다() {
        NotificationRecipient recipient = new NotificationRecipient("WD0001", "01012345678", null, null);
        String expected = WardNotificationText.smsLine("약 드실 시간이에요.");
        when(smsSender.trySend("01012345678", expected)).thenReturn(SmsSender.Outcome.SENT);

        channel.send(NotificationType.MEDICATION_REMINDER, recipient,
                NotificationContent.of(WardNotificationText.TITLE, "약 드실 시간이에요.", Map.of()));

        verify(smsSender).trySend("01012345678", "[실버브릿지] 약 드실 시간이에요.");
    }
}
