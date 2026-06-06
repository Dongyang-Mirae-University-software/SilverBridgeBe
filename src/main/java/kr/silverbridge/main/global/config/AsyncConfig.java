package kr.silverbridge.main.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 실행 설정.
 *
 * 용도: 커밋 후(AFTER_COMMIT) 부수 작업을 별도 스레드로 처리해 HTTP 응답 시간에 포함되지 않게 한다.
 *  - 연결 알림 발송(ConnectionNotificationListener) — FCM/WebSocket 지연 분리
 *  - 카카오 가입 접속로그 기록(KakaoRegisterEventListener) — access_logs insert 지연 분리
 *
 * 큐 포화 시 CallerRunsPolicy로 호출 스레드에서 직접 실행하여 알림 유실을 방지한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
