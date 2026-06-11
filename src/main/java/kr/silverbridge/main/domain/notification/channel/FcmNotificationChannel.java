package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.notification.service.FcmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FCM 푸시 채널 구현체. 기존 {@link FcmService#sendToUser}에 발송을 위임한다.
 *
 * <p>리팩토링 전에는 리스너가 {@code FcmService}를 직접 호출했다. 이제는 디스패처가
 * 이 구현체를 통해 호출하므로, FCM이 켜진(기본값) 사용자에 대해서는 기존과 동일하게 동작한다.</p>
 */
@Component
@RequiredArgsConstructor
public class FcmNotificationChannel implements NotificationChannel {

    private final FcmService fcmService;

    @Override
    public NotificationChannelType getType() {
        return NotificationChannelType.FCM;
    }

    @Override
    public boolean send(NotificationRecipient recipient, NotificationContent content) {
        return fcmService.sendToUser(recipient.userId(), content.title(), content.body(), content.data());
    }
}
