package kr.silverbridge.main.domain.notification.channel;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 이메일 알림 채널(2026-10-13). 기존 {@link JavaMailSender}(비밀번호 재설정과 같은 SMTP 설정·타임아웃)를 쓴다.
 *
 * <p><b>안내성 알림 전용</b>이다 - {@link NotificationType#allowedChannels()}가 EMAIL을 허용한 종류
 * (연결·문의 답변·복약 미복용 요약)만 이 채널에 도달한다. 이메일은 즉시성이 보장되지 않으므로 SOS·이상감지
 * 같은 긴급 알림의 전달 수단으로 승격하지 말 것(접수만 돼도 "전달됨"으로 잡혀 문자 대체를 막는다).</p>
 *
 * <p>킬 스위치 {@code notification.email.enabled}가 꺼져 있으면 빈이 만들어지지 않고, 디스패처는 이 채널을
 * 대상 아님으로 건너뛴다(사용자 설정값은 그대로 보존).</p>
 *
 * <p>로그·이력에는 수신 주소·본문·예외 메시지를 남기지 않는다(예외 클래스명만).</p>
 *
 * <p><b>형식(2026-11-20)</b>: multipart/alternative로 텍스트 본문(기존 내용 그대로)과 HTML 본문
 * ({@link EmailNotificationHtml})을 함께 보낸다. 보낸 사람은 {@code "CareAI" <spring.mail.username>}
 * (표시 이름 {@code notification.email.from-name}). 제목이 서비스 이름과 같으면(피보호자 알림 제목
 * {@link WardNotificationText#TITLE}) 접두어를 붙이지 않는다 - {@code [CareAI] CareAI} 중복 방지.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true")
public class EmailNotificationChannel implements NotificationChannel {

    /** 카카오가 이메일을 주지 않을 때 서버가 부여하는 가상 이메일(KakaoAuthService). 실제 메일함이 없다. */
    private static final Pattern VIRTUAL_KAKAO_EMAIL =
            Pattern.compile("^kakao_\\d+@kakao\\.com$", Pattern.CASE_INSENSITIVE);

    static final String SERVICE_NAME = "CareAI";
    static final String SUBJECT_PREFIX = "[" + SERVICE_NAME + "] ";
    static final String FOOTER_LINE_1 = "이 메일은 CareAI 알림 설정에서 이메일 알림을 켜셔서 발송되었습니다.";
    static final String FOOTER_LINE_2 = "알림 설정에서 이메일 알림을 끌 수 있습니다.";
    static final String FOOTER = "\n\n- " + FOOTER_LINE_1 + "\n- " + FOOTER_LINE_2;

    private final JavaMailSender mailSender;
    private final EmailSendExecutor sendExecutor;
    /** 보낸 사람 주소(SMTP 인증 계정). 비어 있으면 From을 지정하지 않는다(SMTP 서버가 채운다 - 기존 동작). */
    private final String fromAddress;
    private final String fromName;

    @Autowired
    public EmailNotificationChannel(JavaMailSender mailSender, EmailSendExecutor sendExecutor,
                                    @Value("${spring.mail.username:}") String fromAddress,
                                    @Value("${notification.email.from-name:" + SERVICE_NAME + "}") String fromName) {
        this.mailSender = mailSender;
        this.sendExecutor = sendExecutor;
        this.fromAddress = fromAddress == null ? "" : fromAddress.strip();
        this.fromName = fromName == null || fromName.isBlank() ? SERVICE_NAME : fromName.strip();
    }

    @Override
    public NotificationChannelType getType() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public ChannelResult send(NotificationType type, NotificationRecipient recipient, NotificationContent content) {
        String to = recipient.email();
        if (to == null || to.isBlank()) {
            log.warn("이메일 알림 건너뜀(주소 없음): userId={}", recipient.userId());
            return ChannelResult.failed(ChannelFailureReason.NO_EMAIL);
        }
        if (VIRTUAL_KAKAO_EMAIL.matcher(to.trim()).matches()) {
            // 메일함이 없는 계정이라 고장이 아니라 "이 채널의 대상이 아님"이다 - 실패로 세면 관리자 실패율을
            // 부풀려 진짜 장애가 묻힌다(알림 이력 불변 규칙 ③). 이력에는 남기지 않는다.
            log.debug("이메일 알림 대상 아님(카카오 가상 이메일): userId={}", recipient.userId());
            return ChannelResult.notApplicable();
        }

        MimeMessage message;
        try {
            message = buildMessage(to.trim(), content);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // 메일 객체를 만들지 못해도 발송 흐름을 막지 않고 실패로 기록한다. 예외 메시지에 주소가 섞일 수 있어 클래스명만.
            log.warn("이메일 알림 작성 실패: userId={}, exception={}", recipient.userId(), e.getClass().getSimpleName());
            return ChannelResult.failed(ChannelFailureReason.PROVIDER_ERROR);
        }
        try {
            // 호출 전체 시간 제한 안에서 실행(점검 M-1). 초과·풀 포화는 통신 오류와 같게 실패 처리.
            boolean completed = sendExecutor.run(() -> mailSender.send(message));
            return completed ? ChannelResult.delivered() : ChannelResult.failed(ChannelFailureReason.PROVIDER_ERROR);
        } catch (MailException e) {
            // 예외 메시지에는 수신 주소가 들어갈 수 있어 클래스명만 남긴다.
            log.warn("이메일 알림 발송 실패: userId={}, exception={}", recipient.userId(), e.getClass().getSimpleName());
            return ChannelResult.failed(ChannelFailureReason.PROVIDER_ERROR);
        }
    }

    private MimeMessage buildMessage(String to, NotificationContent content)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setTo(to);
        if (!fromAddress.isEmpty()) {
            helper.setFrom(fromAddress, fromName);
        }
        String title = title(content);
        helper.setSubject(subject(title));
        helper.setText(text(content),
                EmailNotificationHtml.render(SERVICE_NAME, title, content.body(), FOOTER_LINE_1, FOOTER_LINE_2));
        return message;
    }

    private static String title(NotificationContent content) {
        String title = content.title();
        return title == null || title.isBlank() ? "알림" : title.strip();
    }

    /** 제목이 서비스 이름 그대로면(피보호자 알림) 접두어를 겹치지 않는다. */
    static String subject(String title) {
        return SERVICE_NAME.equals(title) ? title : SUBJECT_PREFIX + title;
    }

    private static String text(NotificationContent content) {
        String body = content.body() == null ? "" : content.body();
        return body + FOOTER;
    }
}
