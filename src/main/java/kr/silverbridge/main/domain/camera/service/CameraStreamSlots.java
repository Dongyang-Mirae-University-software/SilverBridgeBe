package kr.silverbridge.main.domain.camera.service;

import kr.silverbridge.main.domain.camera.config.CameraStreamProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 열린 영상 중계의 자리(동시 시청 상한)와 종료 처리.
 *
 * <p>영상 1건이 요청 스레드 1개와 AI 연결 1개를 시청 내내 붙든다. 상한 없이 열면 요청 스레드가 바닥나
 * SOS·로그인 같은 일반 API까지 멈춘다 - 그래서 서버 전체·보호자 1명 두 단계로 막는다.</p>
 *
 * <p><b>종료</b>: 우아한 종료는 진행 중 요청이 끝나길 기다리는데 영상은 스스로 끝나지 않는다. 웹 서버보다
 * 먼저 멈추는 단계({@link #getPhase()} 최댓값)에서 열린 AI 연결을 모두 닫아 중계 루프를 빠져나오게 한다 -
 * 그러지 않으면 배포마다 종료가 최대 대기 시간만큼 늘어나 큐에 있던 알림 처리까지 밀린다.</p>
 *
 * <p>상한은 이 인스턴스 기준이다(서버 1대 운영 전제). 여러 대로 늘리면 Redis 등 공유 카운터로 옮길 것.</p>
 */
@Slf4j
@Component
public class CameraStreamSlots implements SmartLifecycle {

    private final int maxPerUser;
    private final Semaphore global;
    private final Map<String, Integer> perUser = new ConcurrentHashMap<>();
    private final Set<Slot> active = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    public CameraStreamSlots(CameraStreamProperties properties) {
        this.maxPerUser = properties.getMaxStreamsPerUser();
        this.global = new Semaphore(properties.getMaxConcurrentStreams());
    }

    /**
     * 자리를 잡는다. 반드시 {@link Slot#close()}로 돌려줘야 한다(try-with-resources).
     *
     * @throws CustomException 상한 초과 429, 종료 중 503
     */
    public Slot acquire(String userId) {
        if (!running) {
            throw new CustomException(ErrorCode.CAMERA_STREAM_UNAVAILABLE);
        }
        boolean[] reserved = {false};
        perUser.compute(userId, (key, count) -> {
            int current = count == null ? 0 : count;
            if (current >= maxPerUser) {
                return count;
            }
            reserved[0] = true;
            return current + 1;
        });
        if (!reserved[0]) {
            log.info("[CAMERA-STREAM-LIMIT] 보호자 동시 시청 상한: userId={}, max={}", userId, maxPerUser);
            throw new CustomException(ErrorCode.CAMERA_STREAM_LIMIT_EXCEEDED);
        }
        if (!global.tryAcquire()) {
            releaseUser(userId);
            log.warn("[CAMERA-STREAM-LIMIT] 서버 전체 동시 시청 상한 - 요청 거부: userId={}", userId);
            throw new CustomException(ErrorCode.CAMERA_STREAM_LIMIT_EXCEEDED);
        }
        Slot slot = new Slot(userId);
        active.add(slot);
        return slot;
    }

    /** 종료 중이면 중계 루프가 스스로 빠져나온다. */
    public boolean isAccepting() {
        return running;
    }

    /** 테스트·관측용. */
    int activeCount() {
        return active.size();
    }

    private void releaseUser(String userId) {
        perUser.computeIfPresent(userId, (key, count) -> count <= 1 ? null : count - 1);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (!active.isEmpty()) {
            log.info("[CAMERA-STREAM] 종료 - 열린 영상 중계 {}건의 AI 연결을 닫음", active.size());
        }
        active.forEach(Slot::closeConnection);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;   // 웹 서버 우아한 종료보다 먼저 멈춘다
    }

    /** 영상 1건의 자리. 닫으면 자리를 돌려주고 붙여 둔 AI 연결도 닫는다(여러 번 닫아도 안전). */
    public final class Slot implements AutoCloseable {

        private final String userId;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile Closeable connection;

        private Slot(String userId) {
            this.userId = userId;
        }

        /** 종료 처리에서 닫을 AI 연결을 붙인다. */
        public void attach(Closeable connection) {
            this.connection = connection;
            if (!running) {
                closeConnection();   // 붙이는 사이 종료가 시작됐으면 바로 닫는다
            }
        }

        private void closeConnection() {
            Closeable current = this.connection;
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (IOException | RuntimeException e) {
                log.debug("[CAMERA-STREAM] AI 연결 닫기 중 오류(무시): {}", e.getClass().getSimpleName());
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            closeConnection();
            active.remove(this);
            global.release();
            releaseUser(userId);
        }
    }
}
