package kr.silverbridge.main.domain.notification.listener;

import kr.silverbridge.main.domain.notification.service.FcmService;
import kr.silverbridge.main.domain.user.event.PasswordChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 비밀번호 변경·재설정(PasswordChangedEvent) 시 그 사용자의 FCM 토큰을 전부 지운다 (USER-G02, 2026-10-02).
 * <p>
 * 비밀번호 변경은 탈취 의심 시의 복구 수단인데, 토큰 무효화만으로는 탈취자 기기에 등록된 FCM 토큰이 남아
 * 피보호자의 SOS·화재 알림(이름·장소 포함)이 그 기기로 계속 간다(스테일 정리 60일까지). 탈취자는 401이라
 * 재등록할 수 없고, 본인 기기는 다시 로그인하며 재등록한다.
 * <p>
 * 동기 AFTER_COMMIT 리스너 - {@link FcmService#deleteAllTokens}가 REQUIRES_NEW라 이미 커밋된 트랜잭션에
 * 합류하지 않는다(CLAUDE.md §8 H-1). best-effort - 실패해도 비밀번호 변경은 이미 커밋됐으니 응답을 깨뜨리지 않는다.
 * 의존 방향: user(이벤트 발행) → notification(수신). 역방향 import 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordChangedFcmListener {

    private final FcmService fcmService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handlePasswordChanged(PasswordChangedEvent event) {
        try {
            fcmService.deleteAllTokens(event.userId());
        } catch (RuntimeException e) {
            log.warn("[PW-CHANGE] FCM 토큰 정리 실패 - 다른 기기 푸시가 남을 수 있음 userId={} error={}",
                    event.userId(), e.getClass().getSimpleName());
        }
    }
}
