package kr.silverbridge.main.domain.notification.service;

import kr.silverbridge.main.domain.notification.entity.NotificationLog;
import kr.silverbridge.main.domain.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 발송 이력 기록.
 *
 * <p><b>REQUIRES_NEW가 필수다.</b> 디스패처는 AFTER_COMMIT 리스너 안에서도 불린다
 * ({@code MedicationWithdrawalListener}는 동기 AFTER_COMMIT). 그 시점엔 이미 커밋된 트랜잭션의 자원이
 * 스레드에 남아 있어, 기본 전파(REQUIRED)로 저장하면 그 트랜잭션에 합류한 채 다시 커밋되지 않아
 * 기록이 조용히 사라진다.</p>
 *
 * <p>기록 실패는 발송을 되돌리지 않는다 - 호출자(디스패처)가 예외를 잡아 WARN만 남긴다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogRepository notificationLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(NotificationLog notificationLog) {
        notificationLogRepository.save(notificationLog);
    }
}
