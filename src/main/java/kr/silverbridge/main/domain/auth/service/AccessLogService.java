package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.entity.AccessLog;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccessLogService {

    private final AccessLogRepository accessLogRepository;

    // 접속 로그 저장 (IP, UserAgent 포함)
    // REQUIRES_NEW: 외부 트랜잭션이 롤백되어도 로그는 독립적으로 저장
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String userId, AccessAction action, String ipAddress, String userAgent) {
        accessLogRepository.save(AccessLog.builder()
                .userId(userId)
                .action(action)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build());
    }

    // 접속 로그 저장 (IP, UserAgent 없이)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String userId, AccessAction action) {
        log(userId, action, null, null);
    }
}
