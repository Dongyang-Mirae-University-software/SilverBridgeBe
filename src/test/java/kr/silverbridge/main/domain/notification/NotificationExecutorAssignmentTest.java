package kr.silverbridge.main.domain.notification;

import kr.silverbridge.main.domain.anomaly.listener.AnomalyClipCleanupListener;
import kr.silverbridge.main.domain.anomaly.listener.AnomalyClipListener;
import kr.silverbridge.main.domain.anomaly.listener.AnomalyNotificationListener;
import kr.silverbridge.main.domain.auth.listener.KakaoRegisterEventListener;
import kr.silverbridge.main.domain.connection.listener.ConnectionNotificationListener;
import kr.silverbridge.main.domain.inquiry.listener.InquiryNotificationListener;
import kr.silverbridge.main.domain.medication.listener.MedicationIntakeNotificationListener;
import kr.silverbridge.main.domain.sos.listener.SosNotificationListener;
import kr.silverbridge.main.global.config.AsyncConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 리스너가 어느 executor를 쓰는지 고정한다 (2026-10-02 QA SOS-G13·XCUT-G11).
 *
 * <p>SOS·이상감지 발생 알림은 긴급 전용 풀, 나머지는 일반 풀이다. 긴급 리스너를 일반 풀로 되돌리면
 * 연결·문의·복약 알림이 FCM·Solapi 지연으로 쌓일 때 SOS·화재 알림이 그 뒤에 줄을 선다.</p>
 */
class NotificationExecutorAssignmentTest {

    private static final String URGENT = "urgentNotificationExecutor";
    private static final String GENERAL = "notificationExecutor";
    private static final String CLIP = "clipExecutor";

    @Test
    @DisplayName("SOS·이상감지 발생 알림 리스너는 긴급 전용 executor를 쓴다")
    void 긴급_리스너는_긴급_executor() {
        assertThat(asyncQualifiers(SosNotificationListener.class)).containsExactly(URGENT);
        assertThat(asyncQualifiers(AnomalyNotificationListener.class)).containsExactly(URGENT);
    }

    @Test
    @DisplayName("연결·문의·복약 체크·카카오 가입 리스너는 일반 executor를 그대로 쓴다")
    void 일반_리스너는_일반_executor() {
        for (Class<?> listener : List.of(ConnectionNotificationListener.class, InquiryNotificationListener.class,
                MedicationIntakeNotificationListener.class, KakaoRegisterEventListener.class)) {
            assertThat(asyncQualifiers(listener)).as(listener.getSimpleName()).containsOnly(GENERAL);
        }
    }

    @Test
    @DisplayName("이상감지 클립 생성은 전용 executor, 클립 삭제(탈퇴·카메라)는 동기 리스너다 - 2026-10-04")
    void 클립_리스너_executor() {
        // AI 응답을 최대 20초 기다리므로 긴급 알림 풀에 태우면 화재 알림이 그 뒤에 줄을 선다
        assertThat(asyncQualifiers(AnomalyClipListener.class)).containsExactly(CLIP);
        // 탈퇴는 커밋 직후 purge가 행을 지우므로 비동기면 지울 파일을 알 수 없다
        assertThat(asyncQualifiers(AnomalyClipCleanupListener.class)).isEmpty();
    }

    @Test
    @DisplayName("리스너가 가리키는 executor 이름은 AsyncConfig에 빈으로 존재한다(오타 시 기본 executor로 새지 않게)")
    void executor_이름이_빈으로_존재() {
        Set<String> beanNames = Arrays.stream(AsyncConfig.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Bean.class))
                .filter(b -> b != null)
                .flatMap(b -> Arrays.stream(b.name()))
                .collect(Collectors.toSet());
        assertThat(beanNames).contains(URGENT, GENERAL, CLIP);
    }

    private static Set<String> asyncQualifiers(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map((Method m) -> m.getAnnotation(Async.class))
                .filter(a -> a != null)
                .map(Async::value)
                .collect(Collectors.toSet());
    }
}
