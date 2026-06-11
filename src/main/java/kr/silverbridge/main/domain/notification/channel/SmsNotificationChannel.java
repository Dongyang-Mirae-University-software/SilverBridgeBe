package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.auth.service.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SMS 알림 채널 구현체. 기존 {@link SmsSender}(Solapi 래퍼)에 발송을 위임한다.
 *
 * <p>주의: SMS는 지금까지 <b>인증(회원가입·비밀번호 재설정)</b> 용도로만 쓰였고 연결 알림에는
 * 쓰이지 않았다. 이 채널은 "알림" 용도의 신규 추가이며, 기본값이 OFF이므로 사용자가 명시적으로
 * 켜기 전에는 발송되지 않는다(기존 동작 보존). 인증 SMS는 디스패처를 경유하지 않으므로
 * 사용자 설정과 무관하게 항상 발송된다.</p>
 *
 * <p>{@code SmsSender}는 무상태 인프라 래퍼라 도메인 경계를 넘어 주입한다. 향후 global/infra로
 * 추출하는 것은 별도 정리 과제로 남긴다(2·3단계 인계).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsNotificationChannel implements NotificationChannel {

    private final SmsSender smsSender;

    @Override
    public NotificationChannelType getType() {
        return NotificationChannelType.SMS;
    }

    @Override
    public boolean send(NotificationRecipient recipient, NotificationContent content) {
        if (recipient.phone() == null || recipient.phone().isBlank()) {
            log.warn("SMS 알림 건너뜀(전화번호 없음): userId={}", recipient.userId());
            return false;
        }
        smsSender.send(recipient.phone(), buildText(content));
        return true;
    }

    // SMS는 title/body 구분이 없어 한 줄로 합친다. body만 있으면 body만 발송.
    private String buildText(NotificationContent content) {
        if (content.title() == null || content.title().isBlank()) {
            return content.body();
        }
        return "[" + content.title() + "] " + content.body();
    }
}
