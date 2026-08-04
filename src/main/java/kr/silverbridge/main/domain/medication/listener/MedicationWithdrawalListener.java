package kr.silverbridge.main.domain.medication.listener;

import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.medication.service.MedicationWithdrawalService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.entity.User;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import kr.silverbridge.main.global.websocket.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * 회원 탈퇴 시 그 사람이 등록한 복약 일정을 정리하고, <b>남은 보호자</b>에게 중지 사실을 알리는 리스너.
 *
 * <p><b>왜 알리는가</b>: 약은 피보호자가 실제로 복용 중인 것이라, 등록자가 탈퇴했다고 조용히 사라지면
 * 피보호자 화면에서도 그 약이 없어진다. 게다가 피보호자는 스스로 약을 등록할 수 없어(등록은 보호자 전용)
 * 남은 보호자가 알아채지 못하면 복구되지 않는다. 그래서 <b>조치할 수 있는 사람</b>에게만 알린다 —
 * 피보호자 본인에게는 보내지 않는다(할 수 있는 일이 없는 알림은 불안만 준다).</p>
 *
 * <p><b>동기 AFTER_COMMIT</b>이다({@code @Async} 아님). 탈퇴는 커밋 직후 컨트롤러가 회원 행을 hard delete
 * (purge)하므로, 비동기로 미루면 purge의 FK CASCADE가 약을 먼저 지워 "몇 건인지" 셀 수 없게 될 수 있다.
 * {@code UserWithdrawalConnectionListener}(상대방 알림)와 같은 이유·같은 형태다.</p>
 *
 * <p><b>best-effort</b>: 예외를 밖으로 내보내지 않는다 — 여기서 실패가 새어 나가면 나머지 리스너와 purge까지
 * 막혀 좀비 계정(M-S1-1)이 된다. 실패해도 약 행은 purge의 FK CASCADE가 회수하며, 유실되는 건 안내 알림뿐이다.
 * 같은 이유로 <b>스윕 purge</b>({@code WithdrawnUserPurgeScheduler}) 경로에서는 이 리스너를 거치지 않아
 * 안내가 나가지 않는다 — WITHDRAW 감사로그·연결 해제 알림이 감수한 것과 동일한 한계다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationWithdrawalListener {

    /** 피보호자 이름을 못 찾았을 때의 표시용 폴백(알림 문구에 null이 들어가지 않게). */
    private static final String FALLBACK_WARD_NAME = "피보호자";

    private final MedicationWithdrawalService medicationWithdrawalService;
    private final ConnectionService connectionService;
    private final NotificationDispatcher notificationDispatcher;
    private final WebSocketEventPublisher webSocketEventPublisher;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        try {
            Map<String, Integer> stoppedCountByWard =
                    medicationWithdrawalService.removeMedicationsRegisteredBy(event.userId());

            stoppedCountByWard.forEach((wardId, stoppedCount) ->
                    notifyRemainingGuardians(event.userId(), wardId, stoppedCount));
        } catch (RuntimeException e) {
            log.error("[WITHDRAW] 복약 정리 실패 — purge CASCADE가 회수 예정 userId={}", event.userId(), e);
        }
    }

    /** 탈퇴자를 제외한 그 피보호자의 ACTIVE 보호자에게 중지 안내를 보낸다. 남은 보호자가 없으면 보내지 않는다. */
    private void notifyRemainingGuardians(String withdrawnGuardianId, String wardId, int stoppedCount) {
        List<String> recipients = connectionService.getActiveGuardianIds(wardId).stream()
                .filter(guardianId -> !guardianId.equals(withdrawnGuardianId))
                .toList();
        if (recipients.isEmpty()) {
            return;
        }

        String wardName = userRepository.findById(wardId)
                .map(User::getName)
                .orElse(FALLBACK_WARD_NAME);
        NotificationContent content = NotificationContent.of(
                "복약 일정 중지",
                "탈퇴한 보호자가 등록한 %s님의 약 %d건이 중지되었습니다. 계속 복용해야 한다면 다시 등록해 주세요."
                        .formatted(wardName, stoppedCount),
                Map.of("type", "MEDICATION_STOPPED",
                        "wardId", wardId,
                        "stoppedCount", String.valueOf(stoppedCount)));

        recipients.forEach(guardianId -> {
            webSocketEventPublisher.sendToUser(guardianId, "medication-stopped",
                    Map.of("wardId", wardId, "stoppedCount", String.valueOf(stoppedCount)));
            notificationDispatcher.dispatch(guardianId, NotificationType.MEDICATION_STOPPED, content);
        });

        log.info("[WITHDRAW] 복약 중지 안내: wardId={}, 중지={}건, 수신 보호자={}명",
                wardId, stoppedCount, recipients.size());
    }
}
