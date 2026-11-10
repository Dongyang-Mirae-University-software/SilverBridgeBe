package kr.silverbridge.main.domain.auth.service;

import kr.silverbridge.main.domain.auth.entity.AccessLog;
import kr.silverbridge.main.domain.auth.repository.AccessLogRepository;
import kr.silverbridge.main.global.enums.AccessAction;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AccessLogService {

    // access_log 컬럼 길이 (AccessLog 엔티티와 동일) — 넘으면 DB가 거절해 로그인까지 실패시키므로 먼저 자른다
    static final int IP_MAX_LENGTH = 50;
    static final int USER_AGENT_MAX_LENGTH = 500;

    // 트랜잭션 안에서 요청된 기록을 트랜잭션이 끝난 뒤 쓰는 전용 풀(2026-11-10 전체 점검 2차 H-1).
    // 스레드 2개라 접속 로그가 쓰는 DB 커넥션은 최대 2개다. 알림 풀과 섞지 않는다([NOTIFY-REJECTED]는 알림 유실 전용).
    static final int WRITER_THREADS = 2;
    static final int WRITER_QUEUE = 500;
    private static final long WRITER_SHUTDOWN_WAIT_SECONDS = 5;

    private final AccessLogRepository accessLogRepository;
    private final PlatformTransactionManager transactionManager;
    private final ThreadPoolExecutor writer;

    public AccessLogService(AccessLogRepository accessLogRepository, PlatformTransactionManager transactionManager) {
        this.accessLogRepository = accessLogRepository;
        this.transactionManager = transactionManager;
        AtomicInteger sequence = new AtomicInteger();
        this.writer = new ThreadPoolExecutor(WRITER_THREADS, WRITER_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WRITER_QUEUE),
                task -> {
                    Thread thread = new Thread(task, "access-log-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                // 포화·종료 중이면 버린다(CallerRuns 금지 - 요청 스레드가 다시 두 번째 커넥션을 기다리게 된다)
                (task, pool) -> log.warn("[ACCESS-LOG-REJECTED] 접속 로그 기록 폐기: active={}, queue={}",
                        pool.getActiveCount(), pool.getQueue().size()));
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(WRITER_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("[ACCESS-LOG] 종료 대기 초과 - 남은 기록 폐기: queue={}", writer.getQueue().size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 접속 로그 저장 (IP, UserAgent 포함).
     *
     * <p><b>트랜잭션 안에서 부르면 그 자리에서 쓰지 않는다</b> - 트랜잭션이 끝난 뒤(커밋·롤백 모두) 전용 풀에서 쓴다.
     * 호출 스레드가 바깥 커넥션을 쥔 채 두 번째 커넥션(REQUIRES_NEW)을 요구하면, 동시 요청 수가 풀 크기만큼 되는 순간
     * 전원이 두 번째를 기다리며 풀이 통째로 멈춘다(로그인은 그 안에서 BCrypt까지 돈다 - 2026-11-10 전체 점검 2차 H-1).
     * 바깥 트랜잭션이 롤백돼도 기록은 남는다(재사용 감지처럼 예외로 끝나는 경로). 트랜잭션 밖에서 부르면 바로 쓴다.</p>
     *
     * <p>기록 실패(컬럼 제약·DB 순단·풀 포화)는 호출자에게 전파하지 않는다 - 접속 로그는 부가 기능이다.
     * 뒤따르는 삭제보다 먼저 남아야 하는 기록(탈퇴)은 {@link #logNow}를 쓴다.</p>
     */
    public void log(String userId, AccessAction action, String ipAddress, String userAgent) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    writer.execute(() -> write(userId, action, ipAddress, userAgent));
                }
            });
            return;
        }
        write(userId, action, ipAddress, userAgent);
    }

    /**
     * 호출한 자리에서 바로 쓴다(REQUIRES_NEW). 탈퇴 기록 전용 - 탈퇴 직후 purge가 회원 행을 지우므로 늦게 쓰면
     * FK 위반으로 기록이 사라진다. 바깥 트랜잭션이 있으면 그동안 커넥션 2개를 쓰므로 요청이 몰리는 경로에는 쓰지 말 것.
     */
    public void logNow(String userId, AccessAction action, String ipAddress, String userAgent) {
        write(userId, action, ipAddress, userAgent);
    }

    // REQUIRES_NEW: 바깥 트랜잭션과 분리해 저장. 커밋 시점 예외까지 잡으려고 어노테이션 대신 TransactionTemplate을 쓴다.
    private void write(String userId, AccessAction action, String ipAddress, String userAgent) {
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
