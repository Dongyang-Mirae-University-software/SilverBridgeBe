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
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("[NOTIFY-REJECTED] 알림 executor 포화로 작업 폐기: active={}, queue={}, completed={}",
                        pool.getActiveCount(), pool.getQueue().size(), pool.getCompletedTaskCount()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
