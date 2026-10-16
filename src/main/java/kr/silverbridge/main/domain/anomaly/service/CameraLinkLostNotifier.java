package kr.silverbridge.main.domain.anomaly.service;

import kr.silverbridge.main.domain.anomaly.config.AnomalyProperties;
import kr.silverbridge.main.domain.camera.dto.CameraOwner;
import kr.silverbridge.main.domain.camera.service.CameraService;
import kr.silverbridge.main.domain.connection.service.ConnectionService;
import kr.silverbridge.main.domain.notification.channel.NotificationContent;
import kr.silverbridge.main.domain.notification.dispatch.NotificationDispatcher;
import kr.silverbridge.main.domain.notification.dispatch.NotificationType;
import kr.silverbridge.main.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;

/**
 * AI 연결 끊김 뒤 돌아오지 않은 카메라를 보호자에게 알린다(2026-10-16).
 *
 * <p>AI 서버가 재시작되면 우리 구독이 사라지고, 재접속 뒤 목록에 없는 카메라는 감시가 멈춘 채다. 유예
 * ({@code grace-seconds}) 안에 송출이 복구되어 다시 구독되면 보내지 않는다 - 대부분 이 안에 돌아온다.</p>
 *
 * <ul>
 *   <li>수신자는 <b>ACTIVE 보호자</b>다(피보호자 본인은 기기 앞에 있는 사람이 아닐 수 있어 보호자가 안내받아 조치한다).</li>
 *   <li>카메라당 쿨다운(기본 6시간)은 Redis. 확인에 실패하면 <b>보내지 않는다</b>(fail-closed) - 위험 알림이 아니라
 *       확인 요청이라 장애 중 반복 발송이 더 해롭다.</li>
 *   <li>발송은 {@code notificationExecutor}에서 한다(스케줄러 스레드가 FCM 응답을 기다리지 않는다).</li>
 *   <li>로그에는 sessionId·건수·예외 클래스명만 남긴다.</li>
 * </ul>
 */
@Slf4j
@Component
public class CameraLinkLostNotifier {

    private static final String KEY_PREFIX = "camera:link-lost-notify:";
    private static final String FALLBACK_WARD_NAME = "보호 대상자";
    private static final String FALLBACK_LOCATION = "등록된 카메라";

    private final AnomalyProperties properties;
    private final TaskScheduler taskScheduler;
    private final Executor executor;
    private final CameraService cameraService;
    private final ConnectionService connectionService;
    private final UserRepository userRepository;
    private final NotificationDispatcher dispatcher;
    private final StringRedisTemplate redisTemplate;

    /** 유예 중인 세션. 복구되면 {@link #onRecovered}가 지우고, 작업이 실행될 때 남아 있어야만 발송한다. */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    public CameraLinkLostNotifier(AnomalyProperties properties,
                                  TaskScheduler taskScheduler,
                                  @Qualifier("notificationExecutor") Executor executor,
                                  CameraService cameraService,
                                  ConnectionService connectionService,
                                  UserRepository userRepository,
                                  NotificationDispatcher dispatcher,
                                  StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.taskScheduler = taskScheduler;
        this.executor = executor;
        this.cameraService = cameraService;
        this.connectionService = connectionService;
        this.userRepository = userRepository;
        this.dispatcher = dispatcher;
        this.redisTemplate = redisTemplate;
    }

    /** 재접속 뒤 목록에서 사라진 등록 카메라 세션들. 유예 뒤에도 돌아오지 않으면 알린다. */
    public void onLost(Collection<String> sessionIds) {
        if (!properties.getLinkLostNotify().isEnabled() || sessionIds.isEmpty()) {
            return;
        }
        Duration grace = Duration.ofSeconds(Math.max(0, properties.getLinkLostNotify().getGraceSeconds()));
        for (String sessionId : sessionIds) {
            pending.computeIfAbsent(sessionId, id ->
                    taskScheduler.schedule(() -> notifyIfStillLost(id), Instant.now().plus(grace)));
        }
    }

    /** 세션이 다시 구독됐다 - 유예 중이던 알림을 취소한다. */
    public void onRecovered(String sessionId) {
        ScheduledFuture<?> future = pending.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** AI 연결이 다시 끊겼다 - 상태를 알 수 없으므로 유예 중인 알림을 모두 취소한다(재접속 뒤 다시 판단한다). */
    public void cancelAll() {
        for (String sessionId : List.copyOf(pending.keySet())) {
            onRecovered(sessionId);
        }
    }

    // 스케줄러 스레드에서 호출 - 남아 있을 때만(복구·취소되면 제거돼 있다) 발송을 풀로 넘긴다.
    private void notifyIfStillLost(String sessionId) {
        if (pending.remove(sessionId) == null) {
            return;
        }
        try {
            executor.execute(() -> send(sessionId));
        } catch (RuntimeException e) {
            log.warn("[CAMERA-LINK-LOST-REJECTED] 발송 작업을 넘기지 못함: sessionId={}, error={}",
                    sessionId, e.getClass().getSimpleName());
        }
    }

    void send(String sessionId) {
        try {
            Optional<CameraOwner> owner = cameraService.findOwnerBySessionId(sessionId);
            if (owner.isEmpty()) {
                return;   // 그 사이 카메라가 삭제됐다
            }
            String wardId = owner.get().wardId();
            List<String> guardianIds = connectionService.getActiveGuardianIds(wardId);
            if (guardianIds.isEmpty()) {
                return;   // 알릴 사람이 없으면 쿨다운도 쓰지 않는다
            }
            if (!acquireCooldown(sessionId)) {
                return;
            }
            NotificationContent content = content(wardId, owner.get().label());
            for (String guardianId : guardianIds) {
                try {
                    dispatcher.dispatch(guardianId, wardId, NotificationType.CAMERA_LINK_LOST, content);
                } catch (RuntimeException e) {
                    log.warn("[CAMERA-LINK-LOST-FAILED] 발송 실패: sessionId={}, error={}",
                            sessionId, e.getClass().getSimpleName());
                }
            }
            log.info("[CAMERA-LINK-LOST] 보호자 안내 발송: sessionId={}, 대상={}명", sessionId, guardianIds.size());
        } catch (RuntimeException e) {
            log.warn("[CAMERA-LINK-LOST-FAILED] 처리 실패: sessionId={}, error={}",
                    sessionId, e.getClass().getSimpleName());
        }
    }

    private boolean acquireCooldown(String sessionId) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + sessionId, "1",
                    Duration.ofHours(Math.max(1, properties.getLinkLostNotify().getCooldownHours())));
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            log.warn("[CAMERA-LINK-LOST] 쿨다운 확인 실패 - 보내지 않음(fail-closed): sessionId={}, error={}",
                    sessionId, e.getClass().getSimpleName());
            return false;
        }
    }

    private NotificationContent content(String wardId, String label) {
        String wardName = userRepository.findById(wardId)
                .map(user -> StringUtils.hasText(user.getName()) ? user.getName() : FALLBACK_WARD_NAME)
                .orElse(FALLBACK_WARD_NAME);
        String location = StringUtils.hasText(label) ? label : FALLBACK_LOCATION;
        // 원인을 단정하지 않는다 - 서버는 "목록에 돌아오지 않았다"까지만 안다.
        String body = "%s님 댁 %s 카메라의 영상이 확인되지 않아 감시가 멈춰 있을 수 있습니다. 카메라 기기의 송출 상태를 확인해 주세요."
                .formatted(wardName, location);
        return NotificationContent.of("카메라 연결 확인 필요", body, Map.of(
                "type", "CAMERA_LINK_LOST",
                "wardId", wardId,
                "wardName", wardName,
                "location", location));
    }
}
