package kr.silverbridge.main.domain.notification.dispatch;

import kr.silverbridge.main.domain.notification.channel.NotificationRecipient;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * userId로 채널 발송에 필요한 수신자 식별자(전화번호·이메일)를 한 번에 조회한다.
 *
 * <p>사용자를 찾지 못해도 예외를 던지지 않고 userId만 채운 수신자를 반환한다 —
 * FCM은 userId(토큰 조회 키)만으로 동작하므로 부분 정보로도 가능한 발송은 진행한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRecipientResolver {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public NotificationRecipient resolve(String userId) {
        return userRepository.findById(userId)
                .map(this::toRecipient)
                .orElseGet(() -> {
                    log.warn("알림 수신자 조회 실패(userId={}) — userId만으로 발송 시도", userId);
                    return new NotificationRecipient(userId, null, null);
                });
    }

    private NotificationRecipient toRecipient(User user) {
        return new NotificationRecipient(user.getId(), user.getPhone(), user.getEmail());
    }
}