package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.entity.AccessLog;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogService {

    // access_log 컬럼 길이 (AccessLog 엔티티와 동일) — 넘으면 DB가 거절해 로그인까지 실패시키므로 먼저 자른다
    static final int IP_MAX_LENGTH = 50;
    static final int USER_AGENT_MAX_LENGTH = 500;

    private final AccessLogRepository accessLogRepository;
    private final PlatformTransactionManager transactionManager;

    // 접속 로그 저장 (IP, UserAgent 포함)
    // REQUIRES_NEW: 외부 트랜잭션이 롤백되어도 로그는 독립적으로 저장.
    // 기록 실패(컬럼 제약·DB 순단 등)는 로그인·로그아웃 같은 호출자에게 전파하지 않는다 — 접속 로그는 부가 기능이라
    // 이것 때문에 본 작업이 실패하면 안 된다. 커밋 시점 예외까지 잡으려고 어노테이션 대신 TransactionTemplate을 쓴다.
    public void log(String userId, AccessAction action, String ipAddress, String userAgent) {
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> accessLogRepository.save(AccessLog.builder()
                    .userId(userId)
                    .action(action)
                    .ipAddress(truncate(ipAddress, IP_MAX_LENGTH))
                    .userAgent(truncate(userAgent, USER_AGENT_MAX_LENGTH))
                    .build()));
        } catch (Exception e) {
            // IP·UserAgent·userId 등 식별 정보는 남기지 않는다 — 행동 종류와 예외 클래스만
            log.warn("[ACCESS-LOG-FAILED] action={}, cause={}", action, e.getClass().getSimpleName());
        }
    }

    // 접속 로그 저장 (IP, UserAgent 없이)
    public void log(String userId, AccessAction action) {
        log(userId, action, null, null);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        // 서로게이트 쌍 중간에서 자르지 않는다
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
