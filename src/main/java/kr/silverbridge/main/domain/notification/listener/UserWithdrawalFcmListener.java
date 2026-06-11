package kr.silverbridge.main.domain.notification.listener;

import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 회원 탈퇴(UserWithdrawnEvent) 시 해당 사용자의 FCM 토큰을 정리하는 리스너 (D-USER-3).
 * <p>
 * 탈퇴는 soft delete(INACTIVE)라 user 행이 남아 FK CASCADE가 발동하지 않으므로 명시적으로 삭제한다.
 * 의존 방향: user(이벤트 발행) → notification(수신). 역방향 import 없음.
 * {@link TransactionPhase#AFTER_COMMIT} 에서만 동작하여 탈퇴 트랜잭션이 롤백되면 토큰도 정리되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserWithdrawalFcmListener {

    private final FcmService fcmService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        // best-effort — 예외가 새어 나가면 나머지 리스너·purge까지 막혀 좀비 계정(M-S1-1)이 된다.
        // 실패해도 fcm_tokens 행은 purge의 FK CASCADE가 삭제한다.
        try {
            fcmService.deleteAllTokens(event.userId());
        } catch (RuntimeException e) {
            log.error("[WITHDRAW] FCM 토큰 정리 실패 — purge CASCADE가 회수 예정 userId={}", event.userId(), e);
        }
    }
}
