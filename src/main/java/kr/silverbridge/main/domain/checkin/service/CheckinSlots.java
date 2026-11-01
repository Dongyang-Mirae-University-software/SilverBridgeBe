package kr.silverbridge.main.domain.checkin.service;

import kr.silverbridge.main.domain.checkin.config.CheckinProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 말벗 AI 호출 동시 상한(전체 / 1인, 인스턴스 메모리). AI 호출이 요청 스레드를 최대 30초 붙들므로 상한이 없으면 SOS·로그인까지
 * 밀린다(챗 중계 규칙 ④와 같은 이유). 1인 1건이라 같은 사람의 턴·정리가 겹치지 않는다 - 턴 순서를 지키는 장치이기도 하다.
 */
@Slf4j
@Component
public class CheckinSlots {

    private final int maxPerUser;
    private final Semaphore global;
    private final Map<String, Integer> perUser = new ConcurrentHashMap<>();

    public CheckinSlots(CheckinProperties properties) {
        this.maxPerUser = properties.getMaxPerUser();
        this.global = new Semaphore(properties.getMaxConcurrent());
    }

    /** 반드시 {@link Slot#close()}로 돌려줘야 한다(try-with-resources). 초과 시 429 {@code CHECKIN_BUSY}. */
    public Slot acquire(String userId) {
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
            log.info("[CHECKIN-LIMIT] 사용자 동시 실행 상한: userId={}", userId);
            throw new CustomException(ErrorCode.CHECKIN_BUSY);
        }
        if (!global.tryAcquire()) {
            releaseUser(userId);
            log.warn("[CHECKIN-LIMIT] 서버 전체 동시 실행 상한 - 요청 거부: userId={}", userId);
            throw new CustomException(ErrorCode.CHECKIN_BUSY);
        }
        return new Slot(userId);
    }

    int activeUsers() {
        return perUser.size();
    }

    private void releaseUser(String userId) {
        perUser.computeIfPresent(userId, (key, count) -> count <= 1 ? null : count - 1);
    }

    public final class Slot implements AutoCloseable {
        private final String userId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Slot(String userId) {
            this.userId = userId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                global.release();
                releaseUser(userId);
            }
        }
    }
}
