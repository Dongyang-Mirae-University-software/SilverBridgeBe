package kr.silverbridge.main.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;

/**
 * 비동기 실행 설정.
 *
 * 용도: 커밋 후(AFTER_COMMIT) 부수 작업을 별도 스레드로 처리해 HTTP 응답 시간에 포함되지 않게 한다.
 *  - 연결 알림 발송(ConnectionNotificationListener) — FCM/WebSocket 지연 분리
 *  - 카카오 가입 접속로그 기록(KakaoRegisterEventListener) — access_logs insert 지연 분리
 *
 * 큐 포화 시 호출 스레드에서 실행하지 않는다(CallerRunsPolicy 폐기, 2026-09-11 기술 점검 B-2) - 그러면 AI WS
 * 수신 스레드·HTTP 요청 스레드가 FCM·SMS 응답을 기다리게 되어 @Async를 둔 이유가 사라진다. 대신 큐를 500으로
 * 넓히고, 그래도 넘치면 ERROR 로그를 남기고 폐기한다(어디서 유실됐는지는 남는다).
 *
 * 종료 시 큐에 남은 작업을 최대 20초 기다린다(B-3) - 배포마다 컨테이너가 교체되는데 기다리지 않으면
 * 그 순간 큐에 있던 SOS 알림까지 사라진다. server.shutdown=graceful과 함께 동작한다.
 *
 * 긴급 알림(SOS·이상감지 발생)은 별도 풀을 쓴다(2026-10-02 QA SOS-G13/XCUT-G11) - 한 풀을 나눠 쓰면 연결·문의·
 * 복약 알림이 FCM·Solapi 지연으로 쌓였을 때 SOS·화재 알림이 그 뒤에 줄을 서거나 포화로 폐기된다. 포화·종료
 * 정책은 일반 풀과 같다(폐기 + ERROR 로그, CallerRuns 금지, 종료 대기 20초).
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final int AWAIT_TERMINATION_SECONDS = 20;

    /** 일반 알림(연결·문의·복약 체크) + 카카오 가입 접속로그. */
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        return newExecutor("notificationExecutor", "notify-", 2, 10, 500);
    }

    /**
     * 긴급 알림(SOS·이상감지 발생) 전용.
     *
     * <p>크기 근거: ThreadPoolExecutor는 큐가 찰 때까지 core 이상으로 스레드를 늘리지 않으므로 평상시 동시 처리량은
     * core가 정한다. 한 작업이 수신자 여러 명에게 순차로 보내며 FCM(응답 10초 제한)·SMS 폴백(Solapi SDK 50초)을
     * 기다릴 수 있어 core를 일반 풀(2)보다 넉넉한 4로 둔다 - 앞 작업 하나가 느려도 뒤 SOS가 바로 시작된다.
     * 긴급 알림은 양이 적고 지연이 치명적이라 거부보다 대기를 택해 큐를 200으로 두되(무한 큐는 메모리·무한 지연
     * 위험이라 쓰지 않는다), 큐까지 차면 max 8까지 늘리고 그래도 넘칠 때만 폐기 + ERROR 로그다.</p>
     */
    @Bean(name = "urgentNotificationExecutor")
    public Executor urgentNotificationExecutor() {
        return newExecutor("urgentNotificationExecutor", "notify-urgent-", 4, 8, 200);
    }

    private static ThreadPoolTaskExecutor newExecutor(String name, String threadNamePrefix,
                                                      int corePoolSize, int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("[NOTIFY-REJECTED] 알림 executor 포화로 작업 폐기: executor={}, active={}, queue={}, completed={}",
                        name, pool.getActiveCount(), pool.getQueue().size(), pool.getCompletedTaskCount()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }
}
