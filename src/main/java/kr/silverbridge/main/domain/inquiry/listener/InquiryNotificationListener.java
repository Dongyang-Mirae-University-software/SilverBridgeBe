package kr.silverbridge.main.domain.inquiry.listener;

import kr.silverbridge.main.domain.inquiry.event.InquiryAnsweredEvent;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 문의 답변 완료 이벤트 수신 후 작성자(보호자)에게 알림을 발송하는 리스너.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} + {@code @Async("notificationExecutor")} 조합으로,
 * 답변 저장이 롤백되면 알림이 나가지 않고 발송 지연이 HTTP 응답에 포함되지 않는다
 * (connection/sos 리스너와 동일 패턴).</p>
 *
 * <p>{@link NotificationType#INQUIRY_ANSWERED}는 선택 알림(mandatory=false)이라
 * 사용자 알림 설정을 따른다(기본 FCM ON). 연결 알림과 달리 WebSocket 실시간 발송은 하지 않는다
 * — 문의 답변은 실시간 동기화가 필요한 화면 이벤트가 아니라 푸시 알림만으로 충분하다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryNotificationListener {

    private final NotificationDispatcher notificationDispatcher;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAnswered(InquiryAnsweredEvent event) {
        notificationDispatcher.dispatch(event.authorUserId(), NotificationType.INQUIRY_ANSWERED,
                NotificationContent.of("문의 답변 완료", "문의하신 내용에 답변이 등록되었습니다.",
                        Map.of("type", "INQUIRY_ANSWERED",
                                "inquiryId", String.valueOf(event.inquiryId()))));
    }
}
