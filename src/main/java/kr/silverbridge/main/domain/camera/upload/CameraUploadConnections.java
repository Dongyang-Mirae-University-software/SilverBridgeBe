package kr.silverbridge.main.domain.camera.upload;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.silverbridge.main.domain.camera.config.CameraUploadProperties;
import kr.silverbridge.main.domain.camera.service.CameraUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 열린 송출 연결 목록 + 주기 점검(무수신·재확인).
 *
 * <p>같은 sessionId에는 연결 1개만 허용하고 새 연결이 기존을 교체한다(탭 2개 동시 송출 방지 - AI의 세션당 1연결과 같은 규칙).
 * 한도는 <b>사용자당이 아니라 세션당</b>이다 - 한 피보호자가 방마다 카메라를 가질 수 있다. 단일 인스턴스 메모리 상태다.</p>
 *
 * <p>재확인은 DB를 읽으므로 요청 스레드가 아니라 전용 스케줄러 스레드 한 개에서 돈다(연결은 최대 5개).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CameraUploadConnections {

    private static final long SWEEP_PERIOD_SECONDS = 2;

    private final CameraUploadProperties properties;
    private final CameraUploadService uploadService;

    private final ConcurrentHashMap<String, CameraUploadRelay> bySession = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "camera-upload-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sweep, SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** 서버 종료 - 열린 연결을 1001로 닫는다(FE가 티켓을 다시 받아 재연결한다). */
    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (CameraUploadRelay relay : List.copyOf(bySession.values())) {
            relay.close(1001, "SERVER_SHUTDOWN");
        }
        bySession.clear();
    }

    /** 새 연결을 받을 자리가 있는가(같은 세션의 교체는 자리를 더 쓰지 않는다). */
    boolean hasCapacityFor(String sessionId) {
        return bySession.containsKey(sessionId) || bySession.size() < properties.getMaxConnections();
    }

    int size() {
        return bySession.size();
    }

    /**
     * 연결을 등록하고, 같은 세션의 기존 연결은 1013 REPLACED로 닫는다.
     *
     * @return 자리가 없으면 false(호출자가 새 연결을 1013 BUSY로 닫는다)
     */
    synchronized boolean register(CameraUploadRelay relay) {
        CameraUploadRelay previous = bySession.get(relay.sessionId());
        if (previous == null && bySession.size() >= properties.getMaxConnections()) {
            return false;
        }
        bySession.put(relay.sessionId(), relay);
        if (previous != null && previous != relay) {
            previous.close(1013, "REPLACED");
        }
        return true;
    }

    void unregister(CameraUploadRelay relay) {
        bySession.remove(relay.sessionId(), relay);
    }

    private void sweep() {
        long now = System.nanoTime();
        for (CameraUploadRelay relay : List.copyOf(bySession.values())) {
            try {
                if (relay.isClosed()) {
                    bySession.remove(relay.sessionId(), relay);
                    continue;
                }
                if (relay.isIdle(now)) {
                    relay.close(1000, "IDLE_TIMEOUT");
                    continue;
                }
                if (relay.isRecheckDue(now)) {
                    relay.scheduleNextRecheck(now);
                    if (!uploadService.isStillValid(relay.grant())) {
                        // 계정 정지·탈퇴·역할 변경·비밀번호 변경·카메라 삭제·플래그 제거 - 재연결은 티켓 발급 단계에서 막힌다
                        relay.close(1008, "AUTH_INVALIDATED");
                    }
                }
            } catch (RuntimeException e) {
                // 한 연결의 점검 오류가 다른 연결 점검을 막지 않게 한다. 확인 못 한 연결은 이어 둔다(다음 주기에 다시 본다).
                log.warn("[CAMERA-UPLOAD] 점검 오류: sessionId={}, error={}", relay.sessionId(), e.getClass().getSimpleName());
            }
        }
    }
}
