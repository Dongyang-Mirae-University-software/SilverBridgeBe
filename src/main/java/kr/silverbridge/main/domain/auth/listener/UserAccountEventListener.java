package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.global.enums.AccessAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * user 도메인 이벤트를 받아 토큰/접속로그 등 인증 관련 부수 효과를 처리한다.
 * 의존 방향: user(이벤트 발행) → auth(리스너 처리). 역방향 import 없음.
 *
 * AFTER_COMMIT 단계에서만 실행되어, 외부 트랜잭션이 롤백된 경우엔 부수 효과가 발생하지 않는다.
 * 이로써 user 비활성화는 실패했는데 토큰만 삭제되는 비정합 상태를 방지한다.
 */
@Component
@RequiredArgsConstructor
public class UserAccountEventListener {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessLogService accessLogService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleWithdrawn(UserWithdrawnEvent event) {
        refreshTokenRepository.deleteByUserId(event.userId());
        accessLogService.log(event.userId(), AccessAction.WITHDRAW, event.ipAddress(), event.userAgent());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePasswordChanged(PasswordChangedEvent event) {
        refreshTokenRepository.deleteByUserId(event.userId());
    }
}
