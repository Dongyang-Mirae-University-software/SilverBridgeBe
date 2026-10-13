package kr.silverbridge.main.domain.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Map;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.global.enums.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailNotificationChannelTest {

    @Mock private JavaMailSender mailSender;
    private EmailNotificationChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmailNotificationChannel(mailSender, new EmailSendExecutor(Duration.ofSeconds(2)));
    }

    private final NotificationContent content =
            NotificationContent.of("연결 요청", "홍길동님이 연결을 요청했습니다.", Map.of());

    private static NotificationRecipient recipient(String email) {
        return new NotificationRecipient("GD0001", "01012345678", email, Status.ACTIVE);
    }

    @Test
    @DisplayName("getType은 EMAIL")
    void getType() {
        assertThat(channel.getType()).isEqualTo(NotificationChannelType.EMAIL);
    }

    @Test
    @DisplayName("정상 발송: 제목은 '[실버브릿지] {title}', 본문은 body + 끄는 방법 안내")
    void send_정상() {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        assertThat(result.isDelivered()).isTrue();
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("guardian@example.com");
        assertThat(sent.getSubject()).isEqualTo("[실버브릿지] 연결 요청");
        assertThat(sent.getText()).startsWith("홍길동님이 연결을 요청했습니다.")
                .contains("알림 설정에서 이메일 알림을 끌 수 있습니다");
    }

    @Test
    @DisplayName("제목이 비면 '알림'으로 대체한다")
    void send_제목없음() {
        channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"),
                NotificationContent.of(null, "본문", Map.of()));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("[실버브릿지] 알림");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("주소가 비어 있으면 보내지 않고 NO_EMAIL 실패로 돌려준다")
    void send_주소없음(String email) {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient(email), content);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILED);
        assertThat(result.reason()).isEqualTo(ChannelFailureReason.NO_EMAIL);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kakao_1234567890@kakao.com", "KAKAO_42@Kakao.com"})
    @DisplayName("카카오 가상 이메일은 발송하지 않고 실패가 아닌 '대상 아님'으로 돌려준다 (실패율 오염 방지)")
    void send_가상이메일_대상아님(String email) {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient(email), content);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.NOT_APPLICABLE);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("주소가 null이어도 NO_EMAIL")
    void send_null() {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient(null), content);

        assertThat(result.reason()).isEqualTo(ChannelFailureReason.NO_EMAIL);
    }

    @Test
    @DisplayName("실제 카카오 계정 이메일(가상 형식 아님)은 보낸다")
    void send_카카오_실이메일() {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient("real_user@kakao.com"), content);

        assertThat(result.isDelivered()).isTrue();
    }

    @Test
    @DisplayName("MailException은 PROVIDER_ERROR 고정 코드로 돌려주고 예외를 밖으로 던지지 않는다")
    void send_메일예외() {
        doThrow(new MailSendException("failed to send to guardian@example.com"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILED);
        assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_ERROR);
        assertThat(result.reason().label()).doesNotContain("guardian@example.com");
    }

    @Test
    @DisplayName("SMTP가 제한 시간 안에 끝나지 않으면 기다리지 않고 PROVIDER_ERROR로 돌려준다 (점검 M-1)")
    void send_시간초과() {
        EmailNotificationChannel slow = new EmailNotificationChannel(mailSender,
                new EmailSendExecutor(Duration.ofSeconds(1)));
        doAnswer(invocation -> {
            Thread.sleep(3000);
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        long started = System.nanoTime();
        ChannelResult result = slow.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_ERROR);
        assertThat(elapsedMillis).isLessThan(2500);
    }
}
