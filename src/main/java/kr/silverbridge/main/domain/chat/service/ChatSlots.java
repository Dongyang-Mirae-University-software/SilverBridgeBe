package kr.silverbridge.main.domain.chat.service;

import kr.silverbridge.main.domain.chat.config.ChatRelayProperties;
import kr.silverbridge.main.global.exception.CustomException;
import kr.silverbridge.main.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 진행 중인 챗 전송의 자리(동시 상한). 전송 1건이 요청 스레드를 최대 130초 붙들므로 상한 없이 열면
 * SOS·로그인 같은 일반 API까지 멈춘다 - 서버 전체·보호자 1명 두 단계로 막는다.
 * 상한은 이 인스턴스 기준이다(단일 서버 전제).
 */
@Slf4j
@Component
public class ChatSlots {

    private final int maxPerUser;
    private final Semaphore global;
    private final Map<String, Integer> perUser = new ConcurrentHashMap<>();

    public ChatSlots(ChatRelayProperties properties) {
        this.maxPerUser = properties.getMaxPerUser();
        this.global = new Semaphore(properties.getMaxConcurrent());
    }

    /** 반드시 {@link Slot#close()}로 돌려줘야 한다(try-with-resources). 초과 시 429 {@code CHAT_LIMIT_EXCEEDED}. */
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
            log.info("[CHAT-LIMIT] 보호자 동시 전송 상한: userId={}", userId);
            throw new CustomException(ErrorCode.CHAT_LIMIT_EXCEEDED);
        }
        if (!global.tryAcquire()) {
            releaseUser(userId);
            log.warn("[CHAT-LIMIT] 서버 전체 동시 전송 상한 - 요청 거부: userId={}", userId);
            throw new CustomException(ErrorCode.CHAT_LIMIT_EXCEEDED);
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
