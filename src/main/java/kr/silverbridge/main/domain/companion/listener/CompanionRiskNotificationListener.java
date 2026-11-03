package kr.silverbridge.main.domain.companion.listener;

import kr.silverbridge.main.domain.companion.event.CompanionRiskDetectedEvent;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.notification.entity.NotificationLogResult;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * 안부 대화 위험 신호 → 피보호자의 ACTIVE 보호자 전원에게 알림(2026-11-04, 카드 9의 위험 알림).
 *
 * <p>{@code COMPANION_RISK}는 이상감지와 같은 {@code FORCED_PUSH_PLUS_SETTINGS}다 - 푸시는 끌 수 없고, 어떤 채널로도
 * 전달되지 않으면 문자를 대신 보낸다. 문구에 <b>대답 원문·질병명·위험의 구체 내용을 넣지 않는다</b>(결정 B·K) -
 * "살펴볼 신호가 있었다"와 연락 권유뿐이다. 알림톡 매핑은 두지 않는다(승인 템플릿 없음).</p>
 *
 * <p>커밋 뒤 {@code urgentNotificationExecutor}에서 보낸다(SOS·이상감지와 같은 긴급 풀). 대화당 1번은 발행하는 쪽
 * ({@code risk_notified_at} 선점)이 보장한다. 정지 계정 수신 차단은 디스패처가 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionRiskNotificationListener {

    private static final String FALLBACK_WARD_NAME = "피보호자";

    private final ConnectionService connectionService;
    private final NotificationDispatcher notificationDispatcher;
    private final UserRepository userRepository;

    @Async("urgentNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleRisk(CompanionRiskDetectedEvent event) {
        List<String> guardianIds = connectionService.getActiveGuardianIds(event.wardId());
        if (guardianIds.isEmpty()) {
            log.info("[COMPANION-RISK] 알림 대상 보호자 없음: wardId={}, sessionId={}", event.wardId(), event.sessionId());
            return;
        }
        String wardName = userRepository.findById(event.wardId()).map(User::getName).orElse(FALLBACK_WARD_NAME);
        String title = "안부 말벗 확인 필요";
        String body = wardName + "님과의 안부 대화에서 살펴볼 신호가 있었어요. 연락해 보세요.";
        String smsText = "[실버브릿지] " + body;
        int delivered = 0;
        for (String guardianId : guardianIds) {
            try {
                NotificationLogResult result = notificationDispatcher.dispatch(guardianId, event.wardId(),
                        NotificationType.COMPANION_RISK,
                        NotificationContent.of(title, body,
                                Map.of("type", "COMPANION_RISK",
                                        "wardId", event.wardId(),
                                        "sessionId", String.valueOf(event.sessionId())),
                                smsText));
                if (result != null && result.isDelivered()) {
                    delivered++;
                }
            } catch (RuntimeException e) {
                // 한 보호자 실패가 나머지를 막지 않게 격리한다
                log.error("[COMPANION-RISK] 알림 발송 실패: guardianId={}, sessionId={}, error={}",
                        guardianId, event.sessionId(), e.getClass().getSimpleName());
            }
        }
        log.info("[COMPANION-RISK] 위험 알림 발송: wardId={}, sessionId={}, guardians={}, delivered={}",
                event.wardId(), event.sessionId(), guardianIds.size(), delivered);
    }
}
