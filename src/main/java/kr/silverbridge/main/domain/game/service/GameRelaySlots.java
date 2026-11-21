package kr.silverbridge.main.domain.game.service;

import kr.silverbridge.main.domain.game.config.GameRelayProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 진행 중인 게임 기록 중계의 자리(동시 상한, 2026-11-21 최종 점검 M-4). 조회 1건이 요청 스레드를 최대 8초 붙들므로
 * AI가 느릴 때 새로 고침이 쌓이면 SOS·로그인 같은 일반 API까지 멈춘다 - 챗 중계({@code ChatSlots})와 같이 서버 전체
 * (기본 4)·보호자 1명(기본 2) 두 단계로 막는다. 넘치면 503 {@code GAME_UNAVAILABLE}(AI 장애와 같은 코드 - 화면은
 * 잠시 뒤 다시 시도). 상한은 이 인스턴스 기준이다(단일 서버 전제).
 *
 * <p>자리는 <b>인가 뒤</b>에 잡는다 - 연결되지 않은 요청이 자리를 써서 정상 보호자를 밀어내지 않게.</p>
 */
@Slf4j
@Component
public class GameRelaySlots {

    private final int maxPerUser;
    private final Semaphore global;
    private final Map<String, Integer> perUser = new ConcurrentHashMap<>();

    public GameRelaySlots(GameRelayProperties properties) {
        this.maxPerUser = properties.getMaxPerUser();
        this.global = new Semaphore(properties.getMaxConcurrent());
    }

    /** 반드시 {@link Slot#close()}로 돌려줘야 한다(try-with-resources). 초과 시 503 {@code GAME_UNAVAILABLE}. */
    public Slot acquire(String guardianId) {
        boolean[] reserved = {false};
        perUser.compute(guardianId, (key, count) -> {
            int current = count == null ? 0 : count;
            if (current >= maxPerUser) {
                return count;
            }
            reserved[0] = true;
            return current + 1;
        });
        if (!reserved[0]) {
            log.info("[GAME-LIMIT] 보호자 동시 조회 상한: guardianId={}", guardianId);
            throw new CustomException(ErrorCode.GAME_UNAVAILABLE);
        }
        if (!global.tryAcquire()) {
            releaseUser(guardianId);
            log.warn("[GAME-LIMIT] 서버 전체 동시 조회 상한 - 요청 거부: guardianId={}", guardianId);
            throw new CustomException(ErrorCode.GAME_UNAVAILABLE);
        }
        return new Slot(guardianId);
    }

    int activeUsers() {
        return perUser.size();
    }

    int available() {
        return global.availablePermits();
    }

    private void releaseUser(String guardianId) {
        perUser.computeIfPresent(guardianId, (key, count) -> count <= 1 ? null : count - 1);
    }

    public final class Slot implements AutoCloseable {
        private final String guardianId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Slot(String guardianId) {
            this.guardianId = guardianId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                global.release();
                releaseUser(guardianId);
            }
        }
    }
}
