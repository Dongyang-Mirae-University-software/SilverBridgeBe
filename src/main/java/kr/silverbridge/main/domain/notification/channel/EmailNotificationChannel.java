package kr.silverbridge.main.domain.notification.channel;

import java.util.regex.Pattern;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true")
public class EmailNotificationChannel implements NotificationChannel {

    /** 카카오가 이메일을 주지 않을 때 서버가 부여하는 가상 이메일(KakaoAuthService). 실제 메일함이 없다. */
    private static final Pattern VIRTUAL_KAKAO_EMAIL =
            Pattern.compile("^kakao_\\d+@kakao\\.com$", Pattern.CASE_INSENSITIVE);

    static final String SUBJECT_PREFIX = "[실버브릿지] ";
    static final String FOOTER = "\n\n- 이 메일은 실버브릿지 알림 설정에서 이메일 알림을 켜셔서 발송되었습니다.\n"
            + "- 알림 설정에서 이메일 알림을 끌 수 있습니다.";

    private final JavaMailSender mailSender;

    @Override
    public NotificationChannelType getType() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public ChannelResult send(NotificationType type, NotificationRecipient recipient, NotificationContent content) {
        String to = recipient.email();
        if (to == null || to.isBlank() || VIRTUAL_KAKAO_EMAIL.matcher(to.trim()).matches()) {
            log.warn("이메일 알림 건너뜀(사용 가능한 주소 없음): userId={}", recipient.userId());
            return ChannelResult.failed(ChannelFailureReason.NO_EMAIL);
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to.trim());
        message.setSubject(SUBJECT_PREFIX + subject(content));
        message.setText(text(content));
        try {
            mailSender.send(message);
            return ChannelResult.delivered();
        } catch (MailException e) {
            // 예외 메시지에는 수신 주소가 들어갈 수 있어 클래스명만 남긴다.
            log.warn("이메일 알림 발송 실패: userId={}, exception={}", recipient.userId(), e.getClass().getSimpleName());
            return ChannelResult.failed(ChannelFailureReason.PROVIDER_ERROR);
        }
    }

    private static String subject(NotificationContent content) {
        String title = content.title();
        return title == null || title.isBlank() ? "알림" : title.strip();
    }

    private static String text(NotificationContent content) {
        String body = content.body() == null ? "" : content.body();
        return body + FOOTER;
    }
}
