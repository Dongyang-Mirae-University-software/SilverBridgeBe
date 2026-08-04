package kr.silverbridge.main.domain.medication.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.event.MedicationIntakeChangedEvent;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 피보호자의 복용 체크·해제를 보호자 화면에 실시간 반영하는 리스너.
 *
 * <p><b>WebSocket만 발송한다</b>({@code medication-taken}). FCM·SMS·알림톡은 보내지 않는다 — 복약 체크는
 * 하루 여러 번 일어나는 일상 동작이라 푸시로 알리면 소음이 된다(SOS ACK와 동일한 판단). 채널 추상화
 * ({@code NotificationDispatcher})를 거치지 않는 이유도 같다.</p>
 *
 * <p>수신자는 <b>해당 피보호자의 ACTIVE 보호자 전원 + 피보호자 본인</b>이다. 본인을 포함하는 건 다른 기기·탭의
 * 화면을 맞추기 위해서다. 토픽 {@code /topic/{userId}/medication-taken}은 STOMP 구독 인터셉터의 범용
 * {@code {userId}==세션} 검증으로 보호되므로 별도 등록이 필요 없다.</p>
 *
 * <p>페이로드에 카운트("2/3")를 싣지 않는다 — 프론트가 이미 목록을 갖고 있어 해당 항목만 갱신하면 카운트는
 * 스스로 다시 계산된다. 서버가 카운트를 만들려면 발송 때마다 목록을 다시 조회해야 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationIntakeNotificationListener {

    private final ConnectionService connectionService;
    private final WebSocketEventPublisher webSocketEventPublisher;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleIntakeChanged(MedicationIntakeChangedEvent event) {
        // LinkedHashSet — 중복 수신자를 한 번만, 순서는 보호자 → 피보호자 본인
        Set<String> recipients = new LinkedHashSet<>(connectionService.getActiveGuardianIds(event.wardId()));
        recipients.add(event.wardId());

        // takenAt은 해제 시 null이라 Map.of()를 쓸 수 없다.
        Map<String, String> payload = new HashMap<>();
        payload.put("medicationId", String.valueOf(event.medicationId()));
        payload.put("wardId", event.wardId());
        payload.put("medicationName", event.medicationName());
        payload.put("doseDate", event.doseDate().toString());
        payload.put("taken", String.valueOf(event.taken()));
        payload.put("takenAt", event.takenAt() != null ? event.takenAt().toString() : null);

        // sendToUser는 내부에서 실패를 흡수(WARN)하므로 한 수신자 실패가 나머지를 막지 않는다.
        recipients.forEach(recipient ->
                webSocketEventPublisher.sendToUser(recipient, "medication-taken", payload));

        log.info("복약 체크 실시간 반영: medicationId={}, taken={}, 수신자={}명",
                event.medicationId(), event.taken(), recipients.size());
    }
}
