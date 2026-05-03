package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.repository.RefreshTokenRepository;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import kr.silverbridge.main.domain.user.event.UserWithdrawnEvent;
import kr.silverbridge.main.global.enums.AccessAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * user 도메인 이벤트를 받아 토큰/접속로그 등 인증 관련 부수 효과를 처리한다.
 * 의존 방향: user(이벤트 발행) → auth(리스너 처리). 역방향 import 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccountEventListener {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessLogService accessLogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @EventListener
    public void handleWithdrawn(UserWithdrawnEvent event) {
        refreshTokenRepository.deleteByUserId(event.userId());
        accessLogService.log(event.userId(), AccessAction.WITHDRAW, event.ipAddress(), event.userAgent());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @EventListener
    public void handlePasswordChanged(PasswordChangedEvent event) {
        refreshTokenRepository.deleteByUserId(event.userId());
    }
}
