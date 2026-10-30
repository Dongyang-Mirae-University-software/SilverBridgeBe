package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CameraLinkLostNotifierTest {

    private static final String SESSION = "ward_abcdef0123456789";

    @Mock private TaskScheduler taskScheduler;
    @Mock private CameraService cameraService;
    @Mock private ConnectionService connectionService;
    @Mock private UserRepository userRepository;
    @Mock private NotificationDispatcher dispatcher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private kr.silverbridge.main.domain.notification.service.GuardianNotificationPreferenceService preferenceService;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ScheduledFuture<Object> future;

    private AnomalyProperties properties;
    private CameraLinkLostNotifier notifier;

    @BeforeEach
    void setUp() {
        properties = new AnomalyProperties();
        notifier = new CameraLinkLostNotifier(properties, taskScheduler, Runnable::run, cameraService,
                connectionService, userRepository, dispatcher, redisTemplate, preferenceService);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private Runnable scheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Instant.class));
        return task.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubSchedule() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn((ScheduledFuture) future);
    }

    @Test
    @DisplayName("유예 뒤에도 돌아오지 않으면 ACTIVE 보호자 전원에게 한 번씩 보낸다")
    void 유예뒤_발송() {
        stubSchedule();
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("W1", "거실")));
        when(connectionService.getActiveGuardianIds("W1")).thenReturn(List.of("G1", "G2"));
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findById("W1")).thenReturn(Optional.empty());

        notifier.onLost(Set.of(SESSION));
        scheduledTask().run();

        ArgumentCaptor<NotificationContent> content = ArgumentCaptor.forClass(NotificationContent.class);
        verify(dispatcher).dispatch(eq("G1"), eq("W1"), eq(NotificationType.CAMERA_LINK_LOST), content.capture());
        verify(dispatcher).dispatch(eq("G2"), eq("W1"), eq(NotificationType.CAMERA_LINK_LOST), any());
        assertThat(content.getValue().body()).contains("거실").doesNotContain("꺼졌").doesNotContain("고장")
                .contains("멈춰 있을 수 있습니다.\n카메라 기기의 송출 상태를 확인해 주세요.");
    }

    @Test
    @DisplayName("유예 안에 복구되면 보내지 않는다")
    void 복구되면_취소() {
        stubSchedule();
        notifier.onLost(Set.of(SESSION));
        Runnable task = scheduledTask();

        notifier.onRecovered(SESSION);
        task.run();

        verify(future).cancel(false);
        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("쿨다운 안이면 보내지 않는다")
    void 쿨다운중_스킵() {
        stubSchedule();
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("W1", "거실")));
        when(connectionService.getActiveGuardianIds("W1")).thenReturn(List.of("G1"));
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        notifier.onLost(Set.of(SESSION));
        scheduledTask().run();

        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Redis 장애면 보내지 않는다(fail-closed)")
    void redis_장애_스킵() {
        stubSchedule();
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("W1", "거실")));
        when(connectionService.getActiveGuardianIds("W1")).thenReturn(List.of("G1"));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        notifier.onLost(Set.of(SESSION));
        scheduledTask().run();

        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("킬 스위치가 꺼져 있으면 예약하지 않는다")
    void 킬스위치() {
        properties.getLinkLostNotify().setEnabled(false);

        notifier.onLost(Set.of(SESSION));

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("보호자가 없으면 쿨다운도 쓰지 않는다")
    void 보호자없으면_스킵() {
        stubSchedule();
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("W1", "거실")));
        when(connectionService.getActiveGuardianIds("W1")).thenReturn(List.of());

        notifier.onLost(Set.of(SESSION));
        scheduledTask().run();

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("안내를 끈 보호자는 빼고 보낸다")
    void 끈_보호자_제외() {
        stubSchedule();
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("W1", "거실")));
        when(connectionService.getActiveGuardianIds("W1")).thenReturn(List.of("G1", "G2"));
        when(preferenceService.cameraLinkLostDisabledGuardians()).thenReturn(Set.of("G2"));
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findById("W1")).thenReturn(Optional.empty());

        notifier.onLost(Set.of(SESSION));
        scheduledTask().run();

        verify(dispatcher).dispatch(eq("G1"), eq("W1"), eq(NotificationType.CAMERA_LINK_LOST), any());
        verify(dispatcher, never()).dispatch(eq("G2"), anyString(), any(), any());
    }

    @Test
    @DisplayName("전원이 껐으면 쿨다운도 쓰지 않는다")
    void 전원_끄면_쿨다운_안씀() {
        stubSchedule();
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("W1", "거실")));
        when(connectionService.getActiveGuardianIds("W1")).thenReturn(List.of("G1"));
        when(preferenceService.cameraLinkLostDisabledGuardians()).thenReturn(Set.of("G1"));

        notifier.onLost(Set.of(SESSION));
        scheduledTask().run();

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(dispatcher, never()).dispatch(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("설정 조회가 실패하면 전원에게 보낸다")
    void 설정조회_실패시_전원() {
        stubSchedule();
        when(cameraService.findOwnerBySessionId(SESSION)).thenReturn(Optional.of(new CameraOwner("W1", "거실")));
        when(connectionService.getActiveGuardianIds("W1")).thenReturn(List.of("G1"));
        when(preferenceService.cameraLinkLostDisabledGuardians()).thenThrow(new IllegalStateException("db"));
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(userRepository.findById("W1")).thenReturn(Optional.empty());

        notifier.onLost(Set.of(SESSION));
        scheduledTask().run();

        verify(dispatcher).dispatch(eq("G1"), eq("W1"), eq(NotificationType.CAMERA_LINK_LOST), any());
    }
}
