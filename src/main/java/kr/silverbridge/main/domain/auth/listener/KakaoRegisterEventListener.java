package kr.silverbridge.main.domain.auth.listener;

import kr.silverbridge.main.domain.auth.event.KakaoRegisteredEvent;
import kr.silverbridge.main.domain.auth.service.AccessLogService;
import kr.silverbridge.main.global.enums.AccessAction;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 카카오 가입 완료 후 KAKAO_LOGIN 접속로그를 기록한다.
 *
 * <p>AFTER_COMMIT 단계에서만 실행되어 users 행이 이미 커밋된 상태이므로
 * access_logs → users FK 제약(fk_access_logs_user)을 안전하게 만족한다.
 * 가입 트랜잭션이 롤백되면 이벤트가 발화하지 않아 로그도 남지 않는다.
 *
 * <p>{@code @Async("notificationExecutor")}로 커밋 후 별도 스레드에서 기록해
 * 접속로그 insert 지연이 가입 HTTP 응답 시간에 포함되지 않게 한다
 * (ConnectionNotificationListener와 동일 패턴). 큐 포화 시 CallerRunsPolicy로
 * 호출 스레드에서 직접 실행되어 로그 유실은 없다.
 *
 * <p>접속로그 기록 자체는 REQUIRES_NEW 독립 트랜잭션으로 처리한다
 * (로그 실패가 가입 응답에 영향을 주지 않도록 — AccessLogService 공유 정책과 동일).
 */
@Component
@RequiredArgsConstructor
public class KakaoRegisterEventListener {

    private final AccessLogService accessLogService;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleKakaoRegistered(KakaoRegisteredEvent event) {
        accessLogService.log(event.userId(), AccessAction.KAKAO_LOGIN, event.ipAddress(), event.userAgent());
    }
}