package kr.silverbridge.main.domain.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
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
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class EmailNotificationChannelTest {

    private static final Session SESSION = Session.getInstance(new Properties());
    private static final String FROM = "noreply@example.com";

    @Mock private JavaMailSender mailSender;
    private EmailNotificationChannel channel;

    @BeforeEach
    void setUp() {
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(SESSION));
        channel = channel(FROM, "CareAI", Duration.ofSeconds(2));
    }

    private EmailNotificationChannel channel(String from, String fromName, Duration timeout) {
        return new EmailNotificationChannel(mailSender, new EmailSendExecutor(timeout), from, fromName);
    }

    private final NotificationContent content =
            NotificationContent.of("연결 요청", "홍길동님이 연결을 요청했습니다.", Map.of());

    private static NotificationRecipient recipient(String email) {
        return new NotificationRecipient("GD0001", "01012345678", email, Status.ACTIVE);
    }

    /** 보낸 메시지. 실제 발송기처럼 saveChanges()로 헤더(Content-Type 등)를 확정한 뒤 돌려준다. */
    private MimeMessage sentMessage() throws MessagingException {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        return message;
    }

    /** multipart 안을 따라 내려가 text/plain·text/html 본문을 모은다. */
    private static Map<String, String> bodies(Part part) throws MessagingException, IOException {
        Map<String, String> found = new HashMap<>();
        collect(part, found);
        return found;
    }

    private static void collect(Part part, Map<String, String> found) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            found.put("text", (String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            found.put("html", (String) part.getContent());
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, found);
            }
        }
    }

    @Test
    @DisplayName("getType은 EMAIL")
    void getType() {
        assertThat(channel.getType()).isEqualTo(NotificationChannelType.EMAIL);
    }

    @Test
    @DisplayName("정상 발송: 보낸 사람 'CareAI <계정 주소>', 제목 '[CareAI] {title}', 텍스트·HTML 두 본문")
    void send_정상() throws Exception {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        assertThat(result.isDelivered()).isTrue();
        MimeMessage sent = sentMessage();
        sent.saveChanges();
        assertThat(sent.getAllRecipients()).extracting(a -> ((InternetAddress) a).getAddress())
                .containsExactly("guardian@example.com");
        InternetAddress from = (InternetAddress) sent.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(FROM);
        assertThat(from.getPersonal()).isEqualTo("CareAI");
        assertThat(sent.getSubject()).isEqualTo("[CareAI] 연결 요청");

        Map<String, String> bodies = bodies(sent);
        assertThat(bodies.get("text")).isEqualTo("홍길동님이 연결을 요청했습니다."
                + "\n\n- 이 메일은 CareAI 알림 설정에서 이메일 알림을 켜셔서 발송되었습니다."
                + "\n- 알림 설정에서 이메일 알림을 끌 수 있습니다.");
        assertThat(bodies.get("html"))
                .contains("연결 요청", "홍길동님이 연결을 요청했습니다.")
                .contains("이 메일은 CareAI 알림 설정에서 이메일 알림을 켜셔서 발송되었습니다.")
                .contains("알림 설정에서 이메일 알림을 끌 수 있습니다.");
    }

    @Test
    @DisplayName("최상위가 multipart/alternative이고 텍스트 → HTML 순서다(mixed로 감싸지 않아 첨부 표시가 뜨지 않는다, 점검 L-3)")
    void send_alternative() throws Exception {
        channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        MimeMessage sent = sentMessage();
        assertThat(sent.isMimeType("multipart/alternative")).isTrue();
        assertThat(containsMimeType(sent, "multipart/mixed")).isFalse();
        assertThat(containsMimeType(sent, "multipart/related")).isFalse();
        Multipart root = (Multipart) sent.getContent();
        assertThat(root.getCount()).isEqualTo(2);
        assertThat(root.getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(root.getBodyPart(1).isMimeType("text/html")).isTrue();
        assertThat(root.getBodyPart(1).getContentType()).containsIgnoringCase("charset=UTF-8");
    }

    @Test
    @DisplayName("제목에 줄바꿈이 섞여도 별도 메일 헤더가 생기지 않는다(헤더 끼워 넣기 방지, 점검 L-2)")
    void send_제목줄바꿈_헤더주입없음() throws Exception {
        for (String title : new String[]{"abc\r\nBcc: evil@example.com", "오늘 복약 확인 · 홍\r\nBcc: evil@example.com님"}) {
            org.mockito.Mockito.clearInvocations(mailSender);
            channel.send(NotificationType.MEDICATION_MISSED, recipient("guardian@example.com"),
                    NotificationContent.of(title, "본문", Map.of()));

            MimeMessage sent = sentMessage();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            sent.writeTo(raw);
            assertThat(raw.toString(java.nio.charset.StandardCharsets.US_ASCII).split("\r\n"))
                    .as("원문 헤더 줄: %s", title)
                    .noneMatch(line -> line.regionMatches(true, 0, "Bcc:", 0, 4));
            assertThat(sent.getRecipients(jakarta.mail.Message.RecipientType.BCC)).isNull();
            assertThat(sent.getAllRecipients()).hasSize(1);
        }
    }

    private static boolean containsMimeType(Part part, String type) throws MessagingException, IOException {
        if (part.isMimeType(type)) {
            return true;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                if (containsMimeType(multipart.getBodyPart(i), type)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("보낸 사람 주소 설정이 비어 있으면 From을 지정하지 않는다(SMTP 서버가 채우는 기존 동작)")
    void send_보낸사람주소없음() throws Exception {
        channel("", "CareAI", Duration.ofSeconds(2))
                .send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        assertThat(sentMessage().getFrom()).isNull();
    }

    @Test
    @DisplayName("표시 이름 설정이 비어 있으면 'CareAI'를 쓴다")
    void send_표시이름기본값() throws Exception {
        channel(FROM, " ", Duration.ofSeconds(2))
                .send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        assertThat(((InternetAddress) sentMessage().getFrom()[0]).getPersonal()).isEqualTo("CareAI");
    }

    @Test
    @DisplayName("제목이 비면 '알림'으로 대체한다")
    void send_제목없음() throws Exception {
        channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"),
                NotificationContent.of(null, "본문", Map.of()));

        assertThat(sentMessage().getSubject()).isEqualTo("[CareAI] 알림");
    }

    @Test
    @DisplayName("제목이 서비스 이름과 같으면(피보호자 알림) 접두어를 겹치지 않는다")
    void send_피보호자제목_접두어생략() throws Exception {
        channel.send(NotificationType.CONNECTION_REQUEST, recipient("ward@example.com"),
                NotificationContent.of(WardNotificationText.TITLE, "김보호님이 가족 연결을 요청했어요.", Map.of()));

        MimeMessage sent = sentMessage();
        assertThat(sent.getSubject()).isEqualTo("CareAI");
        // HTML에서는 띠가 이미 서비스 이름을 보여 주므로 같은 제목 줄을 다시 쓰지 않는다.
        String html = bodies(sent).get("html");
        assertThat(html.split("CareAI", -1).length - 1)
                .as("띠 + <title> + 꼬리말 1번 = 3번, 제목 줄 없음").isEqualTo(3);
    }

    @Test
    @DisplayName("이름에 <script>·& 가 있어도 HTML에는 이스케이프되어 들어가고, 텍스트 본문은 원문 그대로다")
    void send_HTML이스케이프() throws Exception {
        channel.send(NotificationType.MEDICATION_MISSED, recipient("guardian@example.com"),
                NotificationContent.of("오늘 복약 확인 · <script>alert(1)</script>님",
                        "톰&제리님이 <b>요청</b>했어요.", Map.of()));

        Map<String, String> bodies = bodies(sentMessage());
        assertThat(bodies.get("html"))
                .doesNotContain("<script>", "<b>요청</b>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;", "톰&amp;제리님이 &lt;b&gt;요청&lt;/b&gt;했어요.");
        assertThat(bodies.get("text")).startsWith("톰&제리님이 <b>요청</b>했어요.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    @DisplayName("주소가 비어 있으면 보내지 않고 NO_EMAIL 실패로 돌려준다")
    void send_주소없음(String email) {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient(email), content);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILED);
        assertThat(result.reason()).isEqualTo(ChannelFailureReason.NO_EMAIL);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kakao_1234567890@kakao.com", "KAKAO_42@Kakao.com"})
    @DisplayName("카카오 가상 이메일은 발송하지 않고 실패가 아닌 '대상 아님'으로 돌려준다 (실패율 오염 방지)")
    void send_가상이메일_대상아님(String email) {
        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient(email), content);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.NOT_APPLICABLE);
        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
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
    @DisplayName("메일 작성 중 MessagingException이 나면 보내지 않고 PROVIDER_ERROR로 돌려준다")
    void send_작성실패() {
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(SESSION) {
            @Override
            public void setSubject(String subject, String charset) throws MessagingException {
                throw new MessagingException("bad header for guardian@example.com");
            }
        });

        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILED);
        assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_ERROR);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("MailException은 PROVIDER_ERROR 고정 코드로 돌려주고 예외를 밖으로 던지지 않는다")
    void send_메일예외() {
        doThrow(new MailSendException("failed to send to guardian@example.com"))
                .when(mailSender).send(any(MimeMessage.class));

        ChannelResult result = channel.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);

        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILED);
        assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_ERROR);
        assertThat(result.reason().label()).doesNotContain("guardian@example.com");
    }

    @Test
    @DisplayName("SMTP가 제한 시간 안에 끝나지 않으면 기다리지 않고 PROVIDER_ERROR로 돌려준다 (점검 M-1)")
    void send_시간초과() {
        EmailNotificationChannel slow = channel(FROM, "CareAI", Duration.ofSeconds(1));
        doAnswer(invocation -> {
            Thread.sleep(3000);
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        long started = System.nanoTime();
        ChannelResult result = slow.send(NotificationType.CONNECTION_REQUEST, recipient("guardian@example.com"), content);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.reason()).isEqualTo(ChannelFailureReason.PROVIDER_ERROR);
        assertThat(elapsedMillis).isLessThan(2500);
    }
}
