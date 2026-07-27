package kr.silverbridge.main.domain.notification.channel;

import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
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
    public boolean send(NotificationType type, NotificationRecipient recipient, NotificationContent content) {
        // 푸시는 문구·data를 그대로 싣는다 — 종류(type)는 클라이언트가 data["type"]으로 이미 받는다.
        return fcmService.sendToUser(recipient.userId(), content.title(), content.body(), content.data());
    }
}
